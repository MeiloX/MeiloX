package com.ljyh.mei.ui.component.player.component.mesh

internal object ShaderSource {
    const val MESH_SHADER = """
uniform shader albumTexture;
uniform shader blendTexture;
uniform float textureMix;
uniform float2 textureSize;
// Cosine and sine pairs for the artwork orbits.
uniform float4 primaryAndUpperOrbits;
uniform float4 lowerOrbitAndAnchor;
uniform float3 toneAndZoom;

float gradientNoise(float2 position) {
    return fract(52.9829189 * fract(dot(position, float2(0.06711056, 0.00583715))));
}

half4 sampleSource(float2 texel) {
    half4 primary = albumTexture.eval(texel);
    if (textureMix <= 0.0) return primary;
    return mix(primary, blendTexture.eval(texel), half(textureMix));
}

half4 sampleOrbit(float2 position, float2 basis) {
    float2 sampleUV = position * basis.x + float2(-position.y, position.x) * basis.y + float2(0.5);
    return sampleSource(sampleUV * textureSize);
}

half4 main(float2 uv) {
    // drawVertices supplies interpolated mesh UVs as shader coordinates. Using these for
    // grain keeps the dither amplitude, with at most 1/255 deviation from screen-space noise.
    float dither = gradientNoise(uv * 1440.0) / 255.0 - 0.5 / 255.0;
    float2 fromCenter = uv - float2(0.5);
    float dist = length(fromCenter);
    // Zoom the composite around the viewport center, including the orbit anchors.
    float2 artworkOffset = fromCenter * toneAndZoom.z;
    float2 centered = uv - float2(0.2) + (artworkOffset - fromCenter);
    half upperWeight = 0.18 * smoothstep(-0.24, 0.26, -artworkOffset.y);
    half lowerWeight = 0.18 * smoothstep(-0.24, 0.26, artworkOffset.y);
    half4 source = sampleOrbit(centered, primaryAndUpperOrbits.xy) * (1.0 - upperWeight - lowerWeight);
    if (upperWeight > 0.0) {
        source += sampleOrbit(centered + float2(0.12, -0.08), primaryAndUpperOrbits.zw) * upperWeight;
    }
    if (lowerWeight > 0.0) {
        source += sampleOrbit(centered + lowerOrbitAndAnchor.zw, lowerOrbitAndAnchor.xy) * lowerWeight;
    }
    // Treat straight RGB, then reapply the coverage supplied by the source field.
    half3 color = source.rgb / max(source.a, half(0.001));
    color = (color - half3(0.5)) * toneAndZoom.x + half3(0.5);
    half luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
    color = mix(half3(luminance), color, toneAndZoom.y);
    // Roll off bright artwork colors without changing their channel ratios.
    half peak = max(color.r, max(color.g, color.b));
    color *= 1.0 - 0.13 * smoothstep(0.53, 1.0, peak);
    color += half3(dither);
    float vignette = 1.0 - smoothstep(0.3, 0.8, dist);
    color *= 0.6 + vignette * 0.4;
    // The captured backdrop is black under the mesh. Flatten coverage against it
    // here so SRC can overwrite overlapping triangles in the same opaque buffer.
    return half4(clamp(color, half3(0.0), half3(1.0)) * source.a, 1.0);
}
"""
}
