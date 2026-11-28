(() => {
    "use strict";

    const parseJson = JSON.parse;
    const stringify = JSON.stringify;
    const base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const requests = new Map();
    const timers = new Map();
    const cancelled = [];
    let nextId = 0;
    let requestCount = 0;
    let lastTurn;
    let started = false;
    let inspect = false;
    let result;
    let entropy;
    let entropyOffset = 0;

    function utf8Bytes(value) {
        const bytes = [];
        for (const character of value) {
            const scalar = character.codePointAt(0);
            const point = scalar >= 0xd800 && scalar <= 0xdfff ? 0xfffd : scalar;
            if (point < 0x80) bytes.push(point);
            else if (point < 0x800) bytes.push(0xc0 | (point >> 6), 0x80 | (point & 63));
            else if (point < 0x10000) bytes.push(0xe0 | (point >> 12), 0x80 | ((point >> 6) & 63), 0x80 | (point & 63));
            else bytes.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 63), 0x80 | ((point >> 6) & 63), 0x80 | (point & 63));
        }
        return bytes;
    }

    function utf8String(bytes) {
        let result = "";
        for (let index = 0; index < bytes.length;) {
            const first = bytes[index++];
            if (first < 0x80) {
                result += String.fromCharCode(first);
                continue;
            }
            const size = first >= 0xc2 && first <= 0xdf ? 1 : first >= 0xe0 && first <= 0xef ? 2 : first >= 0xf0 && first <= 0xf4 ? 3 : 0;
            if (!size) {
                result += "\ufffd";
                continue;
            }
            let point = first & (0x7f >> (size + 1));
            let count = 0;
            for (; count < size && index < bytes.length; ++count) {
                const byte = bytes[index];
                if (byte < 0x80 || byte > 0xbf || !count && (
                    first === 0xe0 && byte < 0xa0 || first === 0xed && byte > 0x9f ||
                    first === 0xf0 && byte < 0x90 || first === 0xf4 && byte > 0x8f
                )) break;
                ++index;
                point = (point << 6) | (byte & 63);
            }
            result += count === size ? String.fromCodePoint(point) : "\ufffd";
        }
        return result;
    }

    function base64Bytes(value) {
        const normalized = value.replace(/\s/g, "").replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
        if (normalized.length % 4 === 1 || /[^A-Za-z0-9+/]/.test(normalized)) throw new Error("Invalid base64");
        const bytes = new Uint8Array(Math.floor(normalized.length * 3 / 4));
        let offset = 0;
        let accumulator = 0;
        let bits = 0;
        for (const character of normalized) {
            accumulator = (accumulator << 6) | base64.indexOf(character);
            bits += 6;
            if (bits >= 8) bytes[offset++] = (accumulator >> (bits -= 8)) & 255;
        }
        return bytes;
    }

    function base64String(bytes) {
        let result = "";
        for (let index = 0; index < bytes.length; index += 3) {
            const first = bytes[index];
            const second = bytes[index + 1] || 0;
            const third = bytes[index + 2] || 0;
            result += base64[first >> 2] + base64[((first & 3) << 4) | (second >> 4)] +
                (index + 1 < bytes.length ? base64[((second & 15) << 2) | (third >> 6)] : "=") +
                (index + 2 < bytes.length ? base64[third & 63] : "=");
        }
        return result;
    }

    class Buffer extends Uint8Array {
        static from(value, encoding = "utf8", length) {
            if (value instanceof ArrayBuffer) return new Buffer(value, Number(encoding) || 0, length);
            if (typeof value !== "string") return new Buffer(value);
            switch (String(encoding).toLowerCase()) {
                case "utf8": case "utf-8": return new Buffer(utf8Bytes(value));
                case "base64": case "base64url": return new Buffer(base64Bytes(value));
                case "latin1": case "binary": return new Buffer(value.split("").map(character => character.charCodeAt(0)));
                case "hex": return new Buffer((value.match(/^(?:[0-9a-f]{2})*/i)[0].match(/../g) || []).map(byte => parseInt(byte, 16)));
                default: throw new Error("Unsupported encoding");
            }
        }

        static alloc(size, fill = 0) { return new Buffer(size).fill(fill); }
        static allocUnsafe(size) { return new Buffer(size); }
        static isBuffer(value) { return value instanceof Buffer; }

        static concat(values, size = values.reduce((total, value) => total + value.length, 0)) {
            const output = Buffer.alloc(size);
            let offset = 0;
            for (const value of values) {
                const part = value.subarray(0, size - offset);
                output.set(part, offset);
                offset += part.length;
                if (offset >= size) break;
            }
            return output;
        }

        toString(encoding = "utf8", start = 0, end = this.length) {
            const bytes = this.subarray(Math.max(0, start), Math.max(0, end));
            switch (String(encoding).toLowerCase()) {
                case "utf8": case "utf-8": return utf8String(bytes);
                case "base64": return base64String(bytes);
                case "base64url": return base64String(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
                case "latin1": case "binary": return Array.from(bytes, byte => String.fromCharCode(byte)).join("");
                case "hex": return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
                default: throw new Error("Unsupported encoding");
            }
        }
    }

    function randomBytes(size) {
        if (!Number.isInteger(size) || size < 0 || !entropy || size > entropy.length - entropyOffset) {
            throw new Error("Secure random byte limit exceeded");
        }
        const output = entropy.slice(entropyOffset, entropyOffset + size);
        entropy.fill(0, entropyOffset, entropyOffset + size);
        entropyOffset += size;
        return output;
    }

    function failure(code) { return Object.assign(new Error("Source operation failed"), {code}); }

    function request(options) {
        if (inspect) return {promise: Promise.reject(failure("PERMISSION_DENIED")), abort() {}};
        if (requestCount >= 16) return {promise: Promise.reject(failure("PLUGIN")), abort() {}};
        requestCount++;
        const id = ++nextId;
        const call = {options, sent: false};
        const promise = new Promise((resolve, reject) => {
            call.resolve = resolve;
            call.reject = reject;
        });
        requests.set(id, call);
        const abort = () => {
            if (!requests.delete(id)) return;
            if (call.sent) cancelled.push(id);
            call.reject(failure("CANCELLED"));
        };
        return {promise, abort};
    }

    function tick() {
        const now = Date.now();
        for (const [id, timer] of timers) {
            if (timer.at > now) continue;
            timers.delete(id);
            Promise.resolve().then(() => timer.callback(...timer.args)).catch(fail);
        }
    }

    function fail(error) {
        if (!result) result = {error: typeof error?.code === "string" ? error.code.slice(0, 32) : "PLUGIN"};
    }

    Object.assign(globalThis, {
        Buffer,
        TextEncoder: class { encode(value = "") { return new Uint8Array(utf8Bytes(String(value))); } },
        TextDecoder: class {
            decode(value = new Uint8Array()) {
                const bytes = ArrayBuffer.isView(value)
                    ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : new Uint8Array(value);
                return utf8String(bytes);
            }
        },
        atob: value => Buffer.from(String(value), "base64").toString("latin1"),
        btoa: value => Buffer.from(String(value), "latin1").toString("base64"),
        crypto: Object.freeze({getRandomValues(value) {
            if (!ArrayBuffer.isView(value)) throw new TypeError("Expected a typed array");
            new Uint8Array(value.buffer, value.byteOffset, value.byteLength).set(randomBytes(value.byteLength));
            return value;
        }}),
        console: Object.freeze({log() {}, info() {}, warn() {}, error() {}, debug() {}, trace() {}, table() {}}),
        setTimeout(callback, delay = 0, ...args) {
            if (typeof callback !== "function") throw new TypeError("Expected a timer callback");
            if (timers.size >= 32) throw failure("PLUGIN");
            const id = ++nextId;
            timers.set(id, {at: Date.now() + Math.min(20_000, Math.max(0, Number(delay) || 0)), callback, args});
            return id;
        },
        clearTimeout: id => { timers.delete(id); }
    });

    Object.defineProperty(globalThis, "__meiloxCompat", {value: Object.freeze({
        request,
        randomBytes,
        turn(id, action) {
            if (action) lastTurn = {id, value: action()};
            if (lastTurn?.id !== id) throw failure("PLUGIN");
            return lastTurn.value;
        },
        start(input) {
            if (started) throw failure("PLUGIN");
            started = true;
            if (typeof input === "string") input = parseJson(input);
            inspect = input.operation === "inspect";
            entropy = base64Bytes(input.entropy);
            delete input.entropy;
            Promise.resolve().then(() => globalThis.__meiloxFormat(input)).then(
                value => { if (!result) result = {result: value ?? null}; }, fail
            );
        },
        resume(id, response) {
            const call = requests.get(id);
            if (!call) return;
            requests.delete(id);
            if (typeof response === "string") response = parseJson(response);
            if (response.error) call.reject(failure(response.error));
            else call.resolve(response);
        },
        tick,
        step() {
            if (result) return stringify(result);
            const network = [];
            let active = 0;
            for (const call of requests.values()) if (call.sent) active++;
            for (const [id, call] of requests) {
                if (active >= 4) break;
                if (call.sent) continue;
                network.push({id, options: call.options});
                call.sent = true;
                active++;
            }
            let due = null;
            for (const timer of timers.values()) due = due === null ? timer.at : Math.min(due, timer.at);
            return stringify({network, cancelled: cancelled.splice(0), delay: due === null ? null : Math.max(0, due - Date.now())});
        }
    })});
})();
