(() => {
    "use strict";

    const runtime = globalThis.__meiloxCompat;
    const cryptoJs = globalThis.__meiloxCryptoJs;
    const events = Object.freeze({inited: "inited", request: "request", updateAlert: "updateAlert"});
    const platforms = ["wy", "tx", "kw", "kg", "mg"];
    const knownQualities = ["128k", "320k", "flac", "flac24bit"];
    const buffer = Object.freeze({
        from(value, encoding) { return Buffer.from(value, encoding); },
        bufToString(bytes, encoding) { return Buffer.from(bytes).toString(encoding); }
    });

    function words(bytes) {
        return cryptoJs.lib.WordArray.create(new Uint8Array(Buffer.from(bytes)));
    }

    function derField(bytes, offset, tag) {
        if (bytes[offset++] !== tag || offset >= bytes.length) throw new Error("Invalid RSA key");
        let size = bytes[offset++];
        if (size & 128) {
            const lengthBytes = size & 127;
            if (lengthBytes < 1 || lengthBytes > 2 || lengthBytes > bytes.length - offset) throw new Error("Invalid RSA key");
            size = 0;
            for (let index = 0; index < lengthBytes; ++index) size = (size << 8) | bytes[offset++];
        }
        if (size > bytes.length - offset) throw new Error("Invalid RSA key");
        return {value: bytes.subarray(offset, offset + size), next: offset + size};
    }

    function rsaEncrypt(value, key) {
        if (typeof key !== "string" || key.length > 4096) throw new Error("Invalid RSA key");
        const pem = Buffer.from(key.replace(/-----[^-]+-----|\s/g, ""), "base64");
        let fields = derField(pem, 0, 48).value;
        if (fields[0] === 48) {
            const algorithm = derField(fields, 0, 48);
            const publicKey = derField(fields, algorithm.next, 3).value;
            if (publicKey[0] !== 0) throw new Error("Invalid RSA key");
            fields = derField(publicKey, 1, 48).value;
        }
        const rawModulus = derField(fields, 0, 2);
        const modulusBytes = rawModulus.value[0] === 0 ? rawModulus.value.subarray(1) : rawModulus.value;
        const exponentBytes = derField(fields, rawModulus.next, 2).value;
        if (modulusBytes.length < 64 || modulusBytes.length > 512 || !exponentBytes.length || exponentBytes.length > 4) {
            throw new Error("Unsupported RSA key size");
        }
        const parseIntBytes = bytes => BigInt("0x" + (Buffer.from(bytes).toString("hex") || "00"));
        const input = Buffer.from(value);
        if (input.length > modulusBytes.length) throw new Error("RSA message is too long");
        let base = parseIntBytes(input);
        const modulus = parseIntBytes(modulusBytes);
        let exponent = parseIntBytes(exponentBytes);
        if (base >= modulus || exponent < 3n) throw new Error("Invalid RSA input");
        let result = 1n;
        while (exponent) {
            if (exponent & 1n) result = (result * base) % modulus;
            base = (base * base) % modulus;
            exponent >>= 1n;
        }
        return Buffer.from(result.toString(16).padStart(modulusBytes.length * 2, "0"), "hex");
    }

    const crypto = Object.freeze({
        md5(value) { return cryptoJs.MD5(typeof value === "string" ? value : words(value)).toString(); },
        randomBytes: runtime.randomBytes,
        aesEncrypt(value, mode, key, iv) {
            const keyBytes = Buffer.from(key);
            if (keyBytes.length !== 16 || !["aes-128-cbc", "aes-128-ecb"].includes(mode)) throw new Error("Unsupported AES options");
            const cbc = mode === "aes-128-cbc";
            if (cbc && Buffer.from(iv).length !== 16) throw new Error("Invalid AES IV");
            const ciphertext = cryptoJs.AES.encrypt(words(value), words(keyBytes), {
                mode: cbc ? cryptoJs.mode.CBC : cryptoJs.mode.ECB,
                iv: cbc ? words(iv) : undefined,
                padding: cryptoJs.pad.Pkcs7
            }).ciphertext;
            return Buffer.from(ciphertext.toString(), "hex");
        },
        rsaEncrypt
    });

    function pairs(value, encode) {
        return Object.entries(value).flatMap(([key, entry]) =>
            (Array.isArray(entry) ? entry : [entry]).map(item => encode(key, item)));
    }

    function request(url, options = {}, callback) {
        if (typeof options === "function") { callback = options; options = {}; }
        if (typeof callback !== "function") throw new TypeError("Expected a request callback");
        const headers = {...options.headers};
        const setType = value => {
            if (!Object.keys(headers).some(key => key.toLowerCase() === "content-type")) headers["Content-Type"] = value;
        };
        let body = options.body;
        let bodyEncoding;
        if (options.formData) {
            const boundary = "meilox-" + Buffer.from(runtime.randomBytes(16)).toString("hex");
            body = pairs(options.formData, (key, value) => {
                if (/[\r\n"\\]/.test(key) || typeof value !== "string") throw new Error("Unsupported multipart field");
                return `--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`;
            }).join("") + `--${boundary}--\r\n`;
            setType("multipart/form-data; boundary=" + boundary);
        } else if (options.form) {
            body = pairs(options.form, (key, value) => encodeURIComponent(key) + "=" + encodeURIComponent(value ?? "")).join("&");
            setType("application/x-www-form-urlencoded");
        } else if (ArrayBuffer.isView(body) || body instanceof ArrayBuffer) {
            body = Buffer.from(body).toString("base64");
            bodyEncoding = "base64";
        } else if (typeof body === "object" && body !== null) {
            body = JSON.stringify(body);
            setType("application/json");
        }
        const call = runtime.request({url, method: String(options.method || "GET").toUpperCase(),
            headers, body, bodyEncoding, timeout: options.timeout});
        let aborted = false;
        const abort = () => { aborted = true; call.abort(); };
        abort.abort = abort;
        Object.defineProperty(abort, "aborted", {get: () => aborted});
        call.promise.then(response => {
            if (aborted) return;
            let payload = response.body;
            const contentType = response.headers?.["content-type"] ?? response.headers?.["Content-Type"];
            if (typeof contentType === "string" && /[+/]json\b/i.test(contentType)) {
                try { payload = JSON.parse(payload); } catch (_) {}
            }
            callback(null, {statusCode: response.status, statusMessage: "", headers: response.headers, body: payload}, payload);
        }, error => { if (!aborted) callback(error, null, null); });
        return abort;
    }

    function capabilities(sources) {
        const properties = {};
        for (const platform of platforms) {
            const source = sources?.[platform];
            if (!Array.isArray(source?.actions) || !source.actions.includes("musicUrl") || !Array.isArray(source.qualitys)) continue;
            const qualities = knownQualities.filter(quality => source.qualitys.includes(quality));
            if (qualities.length) properties[platform] = qualities;
        }
        if (!Object.keys(properties).length) throw new Error("Missing LX playback capability");
        return properties;
    }

    globalThis.__meiloxFormat = async ({source, operation, params, scriptName}) => {
        const tag = key => (source.match(new RegExp("^\\s*\\*?\\s*@" + key + "\\s+([^\\r\\n]+)", "m"))?.[1] || "").trim().slice(0, 128);
        const info = Object.freeze({name: tag("name") || scriptName || "LX", author: tag("author"), version: tag("version"),
            description: tag("description"), homepage: tag("homepage")});
        let handler;
        let initialized = false;
        let ready;
        const setup = new Promise(resolve => { ready = resolve; });
        const lx = Object.freeze({
            env: "mobile", version: "2.0.0", EVENT_NAMES: events, currentScriptInfo: info,
            on(event, callback) {
                if (event !== events.request || typeof callback !== "function") throw new Error("Unsupported LX event");
                handler = callback;
            },
            async send(event, payload) {
                if (event === events.updateAlert) return;
                if (event !== events.inited || initialized || typeof handler !== "function") throw new Error("Invalid LX initialization");
                const declared = capabilities(payload?.sources);
                initialized = true;
                ready(declared);
            },
            request,
            utils: Object.freeze({buffer, crypto})
        });
        Object.defineProperty(globalThis, "lx", {value: lx});
        globalThis.window = globalThis;
        // Keeping the script in its own function avoids collisions with host globals and metadata.
        new Function(source).call(globalThis);
        const qualities = await setup;
        if (operation === "inspect") return {...info, platforms: Object.keys(qualities), qualities};
        if (operation !== "playback.resolve") return null;
        const declared = qualities[params.platform];
        const candidates = Array.isArray(params.qualities) ? params.qualities : [params.quality];
        const requestedQuality = candidates.find(quality => Array.isArray(declared) && declared.includes(quality));
        if (!requestedQuality) return null;
        const uri = await handler.call(lx, {source: params.platform, action: "musicUrl", info: {musicInfo: params.musicInfo, type: requestedQuality}});
        return typeof uri === "string" && uri.length <= 8192 ? {uri, requestedQuality} : null;
    };
})();
