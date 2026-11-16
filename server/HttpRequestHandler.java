import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles one HTTP request on one client connection, then closes the connection.
 *
 * A3: parses the request line, headers and body.
 * A4: performs GET / POST / PUT / DELETE on files under server_data/.
 *
 * Used by HttpServer:  new Thread(new HttpRequestHandler(clientSocket)).start();
 */
public class HttpRequestHandler implements Runnable {

    private static final Path ROOT = Paths.get("server_data").toAbsolutePath().normalize();

    // Shared by all handler threads so that only one thread changes the files at a time.
    private static final Object STORAGE_LOCK = new Object();

    private final Socket socket;

    public HttpRequestHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            try {
                handleRequest(in, out);
            } catch (BadRequestException e) {
                send(out, 400, "Bad Request", null, null);
            } catch (Exception e) {
                send(out, 500, "Internal Server Error", null, null);
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- A3: request parsing

    private void handleRequest(InputStream in, OutputStream out) throws IOException {
        // Request line: METHOD /path HTTP/1.1
        String requestLine = readLine(in);
        if (requestLine == null) {
            throw new BadRequestException();
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) {
            throw new BadRequestException();
        }
        String method = parts[0];
        String path = parts[1];

        // Headers, until the empty line. Names are stored lower-case because
        // HTTP header names are case-insensitive.
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new BadRequestException();
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequestException();
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        // Host and Content-Type are read but not needed for the file operations.
        // Connection: we always close after one response.

        // Body: exactly Content-Length bytes.
        int length = 0;
        if (headers.containsKey("content-length")) {
            try {
                length = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException e) {
                throw new BadRequestException();
            }
            if (length < 0) {
                throw new BadRequestException();
            }
        }
        byte[] body = readExactly(in, length);

        // Run the requested operation.
        if (!method.equals("GET") && !method.equals("POST")
                && !method.equals("PUT") && !method.equals("DELETE")) {
            send(out, 405, "Method Not Allowed", null, null);
            return;
        }
        Path file = resolvePath(path);
        synchronized (STORAGE_LOCK) {
            if (Files.isDirectory(file)) {
                throw new BadRequestException(); // requests for directories are rejected
            }
            switch (method) {
                case "GET":    doGet(file, out); break;
                case "POST":   doPost(file, body, out); break;
                case "PUT":    doPut(file, body, out); break;
                case "DELETE": doDelete(file, out); break;
            }
        }
    }

    /**
     * Reads one line ending in CRLF and returns it without the CRLF.
     * Reads one byte at a time so that no bytes of the body are read by mistake.
     * Returns null if the connection closes first.
     */
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                return line;
            }
            buffer.write(b);
        }
        return null;
    }

    /** A single read() may return only part of the body, so keep reading until we have all of it. */
    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int total = 0;
        while (total < length) {
            int n = in.read(body, total, length - total);
            if (n == -1) {
                throw new BadRequestException(); // body shorter than Content-Length
            }
            total += n;
        }
        return body;
    }

    /** Maps "/users/alice.json" to server_data/users/alice.json. Paths outside server_data are rejected. */
    private Path resolvePath(String path) throws BadRequestException {
        if (!path.startsWith("/")) {
            throw new BadRequestException();
        }
        Path file = ROOT.resolve(path.substring(1)).normalize(); // normalize() removes ".."
        if (!file.startsWith(ROOT) || file.equals(ROOT)) {
            throw new BadRequestException();
        }
        return file;
    }

    // ---------------------------------------------------------------- A4: resource operations

    private void doGet(Path file, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        String contentType = file.toString().endsWith(".json") ? "application/json" : "text/plain";
        send(out, 200, "OK", contentType, Files.readAllBytes(file));
    }

    private void doPost(Path file, byte[] body, OutputStream out) throws IOException {
        if (Files.exists(file)) {
            send(out, 409, "Conflict", null, null);
            return;
        }
        Files.createDirectories(file.getParent()); // create missing parent directories
        Files.write(file, body, StandardOpenOption.CREATE_NEW);
        send(out, 201, "Created", null, null);
    }

    private void doPut(Path file, byte[] body, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        Files.write(file, body); // replaces the old content
        send(out, 204, "No Content", null, null);
    }

    private void doDelete(Path file, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        Files.delete(file);
        send(out, 204, "No Content", null, null);
    }

    // ---------------------------------------------------------------- response

    /** Sends: status line, headers (each ending in CRLF), blank line, then the body if there is one. */
    private void send(OutputStream out, int status, String reason, String contentType, byte[] body)
            throws IOException {
        if (body == null) {
            body = new byte[0];
        }
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n";
        if (contentType != null) {
            head += "Content-Type: " + contentType + "\r\n";
        }
        head += "Content-Length: " + body.length + "\r\n"; // length in bytes, not characters
        head += "Connection: close\r\n";
        head += "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Thrown for a malformed request, invalid length or invalid path; answered with 400. */
    private static class BadRequestException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
