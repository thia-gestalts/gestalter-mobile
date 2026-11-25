package thia.gestalter.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.util.*;

public final class Olymp {
    private Olymp() {}

    @FunctionalInterface
    public interface Transport {
        byte[] request(String origin, String path, String method, Map<String, String> headers, byte[] body)
                throws IOException;
    }

    public static final class HttpFailure extends IOException {
        public final int status;
        public HttpFailure(int status) {
            super("Olymp returned HTTP " + status);
            this.status = status;
        }
    }

    public static final class Session {
        public final String origin;
        public final String id;
        public final String name;
        final String token;
        final byte[] key;

        Session(String origin, String id, String name, String token, byte[] key) {
            this.origin = origin;
            this.id = id;
            this.name = name;
            this.token = token;
            this.key = key;
        }

        String scope() { return Json.tuple(origin, id); }

        JsonObject secret() {
            return Json.object("origin", origin, "id", id, "name", name, "token", token,
                    "key", Crypto.encode(key));
        }

        static Session restore(JsonObject data) {
            return new Session(data.get("origin").getAsString(), data.get("id").getAsString(),
                    data.get("name").getAsString(), Json.string(data, "token", null),
                    Crypto.decode(data.get("key").getAsString()));
        }
    }

    public static String origin(String value) {
        URI uri = URI.create(value.trim()).normalize();
        String host = uri.getHost();
        String scheme = uri.getScheme();
        boolean local = Arrays.asList("localhost", "127.0.0.1", "[::1]", "10.0.2.2").contains(host == null ? "" : host);
        boolean path = "".equals(uri.getRawPath()) || "/".equals(uri.getRawPath());
        if (host == null || !path || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() > 65535 || uri.getPort() == 0
                || !("https".equalsIgnoreCase(scheme) || local && "http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Enter an HTTPS Olymp origin.");
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == 443 && scheme.equals("https") || port == 80 && scheme.equals("http")) port = -1;
        return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    }

    static byte[] request(Transport transport, Session session, String path, String method, byte[] data,
            String type) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + session.token);
        if (!path.equals("logout")) headers.put("X-Olymp-Account", session.id);
        if (type != null) headers.put("Content-Type", type);
        return transport.request(session.origin, path, method, headers, data);
    }

    static JsonElement json(Transport transport, Session session, String path, String method, JsonElement data)
            throws IOException {
        byte[] reply = request(transport, session, path, method, data == null ? null : Json.bytes(data),
                data == null ? null : "application/json");
        return reply.length == 0 ? JsonNull.INSTANCE : Json.parse(reply);
    }

    public static void logout(Transport transport, Session session) throws IOException {
        request(transport, session, "logout", "POST", null, null);
    }

    private static JsonObject post(Transport transport, String origin, String path, JsonObject data)
            throws IOException {
        byte[] response = transport.request(origin, path, "POST", Collections.singletonMap("Content-Type", "application/json"),
                Json.bytes(data));
        return response.length == 0 ? new JsonObject() : Json.parse(response).getAsJsonObject();
    }

    public static Session signIn(Transport transport, String origin, String name, byte[] password,
            boolean signup, String device) throws Exception {
        origin = origin(origin);
        name = name.trim();
        if (!name.matches("[a-zA-Z0-9._-]{1,64}") || password.length == 0)
            throw new IllegalArgumentException("Enter a login name and password.");
        name = name.toLowerCase(Locale.ROOT);
        if (signup) exchange(transport, origin, name, password, device, true);
        return exchange(transport, origin, name, password, device, false);
    }

    private static Session exchange(Transport transport, String origin, String name, byte[] password,
            String device, boolean register) throws Exception {
        String flow = register ? "signup" : "login";
        byte[] started = Crypto.opaqueStart(password, register);
        byte[] state = null;
        byte[] finished = null;
        byte[] export = null;
        byte[] root = null;
        try {
            int split = 2 + ((started[0] & 255) << 8) + (started[1] & 255);
            byte[] message = Arrays.copyOfRange(started, 2, split);
            state = Arrays.copyOfRange(started, split, started.length);
            JsonObject reply = post(transport, origin, flow + "/start",
                    Json.object("name", name, "message", Crypto.encode(message)));
            finished = Crypto.opaqueFinish(password, Crypto.decode(reply.get("message").getAsString()), state,
                    register);
            int keyAt = finished.length - 64;
            export = Arrays.copyOfRange(finished, keyAt, finished.length);
            JsonObject next = Json.object("challenge", reply.get("challenge"),
                    "message", Crypto.encode(Arrays.copyOf(finished, keyAt)));
            if (register) {
                root = Crypto.random(32);
                byte[] context = Json.bytes(Json.tuple("thia-server-unlock", 1, reply.get("id").getAsString(), 0));
                next.addProperty("wrapped", Crypto.encode(Crypto.rootEnvelope(export, context, root, true)));
                post(transport, origin, "signup/finish", next);
                return null;
            }
            next.addProperty("device", device);
            JsonObject unlocked = post(transport, origin, "login/finish", next);
            JsonObject account = unlocked.getAsJsonObject("account");
            String id = account.get("id").getAsString();
            byte[] context = Json.bytes(Json.tuple("thia-server-unlock", 1, id, account.get("generation").getAsLong()));
            root = Crypto.rootEnvelope(export, context, Crypto.decode(unlocked.get("wrapped").getAsString()), false);
            if (root.length != 32) throw new java.security.GeneralSecurityException("Invalid Olymp root key.");
            return new Session(origin, id, account.get("name").getAsString(), unlocked.get("token").getAsString(),
                    Crypto.derive(root, "olymp-content-v1"));
        } finally {
            for (byte[] secret : new byte[][]{started, state, finished, export, root})
                if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }
}
