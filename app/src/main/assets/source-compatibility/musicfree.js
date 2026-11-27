/* MusicFree CommonJS protocol on the isolated MeiloX transport. */
(() => {
  'use strict';

  const bridge = globalThis.__meiloxCompat;
  const packages = Object.assign(Object.create(null), globalThis.__meiloxMusicFreeModules, {
    'crypto-js': globalThis.__meiloxCryptoJs,
    buffer: { Buffer: globalThis.Buffer }
  });
  const MAX_PAGE_ITEMS = 500;
  const MAX_TRACK_BYTES = 24 * 1024;
  const MAX_PAGE_BYTES = 850 * 1024;
  const encoder = new TextEncoder();
  const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const plainObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const string = (value, max) => typeof value === 'string' ? value.trim().slice(0, max) : '';
  const identity = value => typeof value === 'string' || Number.isSafeInteger(value) ? String(value) : '';
  const knownQualities = ['low', 'standard', 'high', 'super'];
  const operationError = code => Object.assign(new Error('MusicFree adapter error'), { code });

  function httpHeaders(values) {
    const headers = Object.create(null);
    if (!plainObject(values)) return headers;
    for (const [name, value] of Object.entries(values)) {
      if (['string', 'number', 'boolean'].includes(typeof value)) headers[name] = String(value);
    }
    return headers;
  }

  function requestConfig(input, config) {
    return typeof input === 'string' ? { ...config, url: input } : { ...input };
  }

  function requestUrl(config) {
    if (typeof config.url !== 'string') throw new TypeError('Invalid HTTP URL');
    let url = config.url;
    if (typeof config.baseURL === 'string' && !/^(?:[a-z][a-z\d+.-]*:)?\/\//i.test(url)) {
      url = config.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '');
    }
    url = url.split('#', 1)[0];
    if (config.params != null) {
      const serializer = typeof config.paramsSerializer === 'function'
        ? config.paramsSerializer : config.paramsSerializer?.serialize;
      const query = serializer ? serializer(config.params) : packages.qs.stringify(config.params, { arrayFormat: 'repeat' });
      if (query) url += (url.includes('?') ? '&' : '?') + query;
    }
    return url;
  }

  function encodeBody(data, headers) {
    if (data == null || typeof data === 'string') return data ?? undefined;
    const contentType = Object.keys(headers).find(name => name.toLowerCase() === 'content-type');
    if (data instanceof URLSearchParams || contentType && /^application\/x-www-form-urlencoded\b/i.test(headers[contentType])) {
      if (!contentType) headers['Content-Type'] = 'application/x-www-form-urlencoded';
      return data instanceof URLSearchParams ? data.toString() : packages.qs.stringify(data);
    }
    if (!contentType) headers['Content-Type'] = 'application/json';
    return JSON.stringify(data);
  }

  function httpError(message, response, code) {
    return Object.assign(new Error(message), { isAxiosError: true, response, code });
  }

  async function axios(input, config) {
    const options = requestConfig(input, config);
    if (['arraybuffer', 'blob', 'stream'].includes(options.responseType)) {
      throw operationError('UNSUPPORTED');
    }
    if (options.signal?.aborted) throw httpError('Request cancelled', undefined, 'ERR_CANCELED');
    const headers = httpHeaders(options.headers);
    const body = encodeBody(options.data, headers);
    const { promise, abort } = bridge.request({
      url: requestUrl(options), method: String(options.method || 'GET').toUpperCase(),
      headers, body, timeout: options.timeout
    });
    const cancel = () => abort();
    options.signal?.addEventListener?.('abort', cancel, { once: true });
    try {
      const response = await promise;
      let data = response.body;
      if (options.responseType !== 'text' && typeof data === 'string') {
        try { data = JSON.parse(data); } catch (_) {}
      }
      const responseHeaders = Object.fromEntries(Object.entries(response.headers ?? {})
        .map(([key, value]) => [key.toLowerCase(), value]));
      if (response.setCookies?.length) responseHeaders['set-cookie'] = response.setCookies;
      const result = {
        data, status: response.status, statusText: response.statusText || '',
        headers: responseHeaders, config: options
      };
      const ok = options.validateStatus === null || (typeof options.validateStatus === 'function'
        ? options.validateStatus(result.status) : result.status >= 200 && result.status < 300);
      if (!ok) throw httpError('HTTP request failed', result);
      return result;
    } finally {
      options.signal?.removeEventListener?.('abort', cancel);
    }
  }

  function addMethods(client, defaults = {}) {
    client.defaults = defaults;
    client.request = client;
    client.getUri = config => requestUrl({ ...client.defaults, ...config });
    for (const method of ['get', 'delete', 'head', 'options']) {
      client[method] = (url, config) => client({ ...config, url, method });
    }
    for (const method of ['post', 'put', 'patch']) {
      client[method] = (url, data, config) => client({ ...config, url, data, method });
    }
    return client;
  }

  addMethods(axios);
  axios.create = defaults => {
    const client = (input, config) => {
      const options = requestConfig(input, config);
      return axios({ ...client.defaults, ...options, headers: { ...client.defaults.headers, ...options.headers } });
    };
    return addMethods(client, { ...defaults });
  };
  axios.isAxiosError = value => value?.isAxiosError === true;
  axios.isCancel = value => value?.code === 'ERR_CANCELED';
  packages.axios = axios;

  globalThis.fetch = async (url, options = {}) => {
    const response = await axios({
      url: String(url), method: options.method, headers: options.headers, data: options.body,
      signal: options.signal, responseType: 'text', validateStatus: null
    });
    return {
      ok: response.status >= 200 && response.status < 300, status: response.status, statusText: response.statusText,
      headers: { get: name => response.headers[String(name).toLowerCase()] ?? null },
      text: async () => response.data,
      json: async () => JSON.parse(response.data)
    };
  };

  function requireModule(name) {
    if (!hasOwn(packages, name)) throw operationError('UNSUPPORTED');
    const module = packages[name];
    if (Object.isExtensible(module) && !hasOwn(module, 'default')) {
      Object.defineProperty(module, 'default', { value: module, configurable: true });
    }
    return module;
  }

  function openPlugin(source, configuration, appVersion) {
    const module = { exports: {} };
    const env = Object.freeze({
      os: 'android', appVersion, lang: 'zh-CN',
      getUserVariables: () => ({ ...configuration }),
      get userVariables() { return this.getUserVariables(); }
    });
    const process = Object.freeze({ platform: 'android', version: appVersion, env });
    const start = new Function('require', '__musicfree_require', 'module', 'exports', 'console', 'env', 'URL', 'process',
      "'use strict';\n" + source);
    start(requireModule, requireModule, module, module.exports, globalThis.console, env, globalThis.URL, process);
    const plugin = module.exports?.default || module.exports;
    if (!plainObject(plugin) || !string(plugin.platform, 100)) throw operationError('INVALID_RESPONSE');
    return plugin;
  }

  function canSearch(plugin) {
    return typeof plugin.search === 'function' && (plugin.supportedSearchType == null ||
      Array.isArray(plugin.supportedSearchType) && plugin.supportedSearchType.includes('music'));
  }

  function inspect(plugin) {
    const keys = new Set();
    const config = [];
    const declarations = Array.isArray(plugin.userVariables) ? plugin.userVariables.slice(0, 64) : [];
    for (const variable of declarations) {
      const key = variable?.key;
      if (typeof key !== 'string' || !/^[A-Za-z][A-Za-z0-9._-]{0,63}$/.test(key) || keys.has(key)) continue;
      keys.add(key);
      const label = string(variable.name, 100) || key;
      config.push({
        key, label,
        secret: variable.secret === true || /password|passwd|token|cookie|secret|api[_.-]?key|authorization|密钥|密码/i.test(key + ' ' + label),
        required: variable.required === true
      });
      if (config.length === 32) break;
    }
    const search = canSearch(plugin);
    return {
      name: string(plugin.platform, 100),
      author: string(plugin.author, 100) || string(plugin.platform, 100),
      version: string(identity(plugin.version), 50) || '0.0.0',
      config, search, playback: search || typeof plugin.getMediaSource === 'function'
    };
  }

  function trackIdentity(raw, keys) {
    const id = identity(raw.id);
    if (!id.trim() || id.length > 2048) return '';
    if (!keys.length) return id;
    const composite = JSON.stringify([id, ...keys.map(key => [key, identity(raw[key])])]);
    return composite.length <= 2048 ? composite : '';
  }

  function transportUrl(value) {
    const url = string(value, 8193);
    if (url.length > 8192) return '';
    return url.startsWith('//') ? 'https:' + url : /^https?:\/\//i.test(url) ? url : '';
  }

  function normalizedTrack(raw, trackId) {
    const artists = Array.isArray(raw.artists) ? raw.artists.slice(0, 50)
      .map(artist => string(artist?.name ?? artist, 1000)).filter(Boolean) : [];
    if (!artists.length) {
      const artist = string(raw.artist, 1000);
      if (artist) artists.push(artist);
    }
    const duration = Number(raw.duration);
    const durationMs = Number.isFinite(duration) && duration > 0 && duration <= 1e9 ? Math.round(duration * 1000) : 0;
    return {
      trackId, title: string(raw.title, 1000), artists, durationMs,
      album: string(raw.album?.title ?? raw.album, 1000),
      artworkUrl: transportUrl(raw.artwork).replace(/^http:\/\/.*/, ''),
      isrc: string(raw.isrc, 100) || undefined,
      explicit: raw.explicit === true
    };
  }

  async function search(plugin, params) {
    if (!canSearch(plugin)) throw operationError('UNSUPPORTED');
    const { query, page, limit, itemOffset = 0 } = params;
    if (typeof query !== 'string' || !Number.isSafeInteger(page) || page < 1 ||
        !Number.isSafeInteger(limit) || limit < 1 || limit > MAX_PAGE_ITEMS ||
        !Number.isSafeInteger(itemOffset) || itemOffset < 0 || itemOffset >= MAX_PAGE_ITEMS) {
      throw operationError('INVALID_RESPONSE');
    }
    const result = await plugin.search(query, page, 'music');
    if (!Array.isArray(result?.data) || result.data.length > MAX_PAGE_ITEMS) throw operationError('INVALID_RESPONSE');
    const keys = Array.isArray(plugin.primaryKey) ? [...new Set(plugin.primaryKey
      .filter(key => typeof key === 'string' && /^(?!id$)[A-Za-z][A-Za-z0-9._-]{0,63}$/.test(key)))]
      .sort().slice(0, 32) : [];
    const seen = new Set();
    const items = [];
    let bytes = 0;
    let nextItemOffset = 0;
    for (let index = 0; index < result.data.length; index++) {
      const media = result.data[index];
      if (!plainObject(media)) continue;
      const trackId = trackIdentity(media, keys);
      if (!trackId || seen.has(trackId)) continue;
      const track = normalizedTrack(media, trackId);
      if (!track.title) continue;
      let snapshot;
      let rowBytes;
      try {
        const row = JSON.stringify({ track, media });
        rowBytes = encoder.encode(row).byteLength;
        if (rowBytes <= MAX_TRACK_BYTES) snapshot = JSON.parse(row);
      } catch (_) { continue; }
      if (!plainObject(snapshot?.media)) continue;
      seen.add(trackId);
      if (index < itemOffset) continue;
      if (items.length === limit || bytes + rowBytes > MAX_PAGE_BYTES) {
        nextItemOffset = index;
        break;
      }
      items.push(snapshot);
      bytes += rowBytes;
    }
    const hasMore = nextItemOffset > 0 || (result.data.length > 0 && result.isEnd !== true);
    return {
      items, hasMore, nextPage: hasMore ? (nextItemOffset > 0 ? page : page + 1) : null, nextItemOffset
    };
  }

  function musicFreeQuality(quality) {
    if (quality === 'standard' || quality === 'exhigh' || quality === 'higher') return 'standard';
    if (quality === 'lossless') return 'high';
    return 'super';
  }

  async function resolve(plugin, params) {
    const media = params.media;
    if (!plainObject(media)) throw operationError('INVALID_RESPONSE');
    const quality = musicFreeQuality(params.quality);
    const hasResolver = typeof plugin.getMediaSource === 'function';
    let resource = null;
    if (hasResolver) {
      const tiers = quality === 'standard' ? ['standard', 'low'] : [quality];
      const original = { platform: plugin.platform, ...media };
      for (const tier of tiers) {
        resource = await plugin.getMediaSource(original, tier);
        if (typeof resource === 'string') resource = { url: resource };
        if (transportUrl(resource?.url)) break;
      }
    }
    if (!transportUrl(resource?.url)) {
      const fallback = knownQualities.slice(0, knownQualities.indexOf(quality) + 1).reverse()
        .map(level => media.qualities?.[level]).find(value => transportUrl(value?.url));
      resource = fallback ?? (hasResolver ? null : media);
    }
    const uri = transportUrl(resource?.url);
    if (!uri) return null;
    const headers = httpHeaders(resource.headers);
    const userAgent = string(resource.userAgent, 2000);
    if (userAgent && !Object.keys(headers).some(key => key.toLowerCase() === 'user-agent')) headers['User-Agent'] = userAgent;
    return {
      uri, quality: 'standard', headers,
      cachePolicy: ['no-cache', 'no-store'].includes(plugin.cacheControl) ? 'NO_STORE' : undefined
    };
  }

  globalThis.__meiloxFormat = ({ source, operation, params, configuration, appVersion }) => {
    const plugin = openPlugin(source, configuration, appVersion);
    switch (operation) {
      case 'inspect': return inspect(plugin);
      case 'catalog.search': return search(plugin, params);
      case 'playback.resolve': return resolve(plugin, params);
      default: throw operationError('UNSUPPORTED');
    }
  };
})();
