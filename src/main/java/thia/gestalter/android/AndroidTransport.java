package thia.gestalter.android;

import java.io.*;
import java.net.*;
import java.util.Map;
import thia.gestalter.core.Olymp;

final class AndroidTransport implements Olymp.Transport {
    private static final int RESPONSE_LIMIT = 5 * 1024 * 1024;

    public byte[] request(String origin, String path, String method, Map<String, String> headers, byte[] body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/v1/" + path).openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            headers.forEach(connection::setRequestProperty);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Olymp.HttpFailure(status);
            if (status == 204) return new byte[0];
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (response.size() + count > RESPONSE_LIMIT) throw new IOException("Olymp response too large.");
                    response.write(buffer, 0, count);
                }
                return response.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
