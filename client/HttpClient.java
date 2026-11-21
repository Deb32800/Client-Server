import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class HttpClient {

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5) {
            printUsage();
            return;
        }

        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port: " + args[1]);
            return;
        }

        String method = args[2].toUpperCase();
        String path = args[3];
        String body = (args.length == 5) ? args[4] : null;

        if (!method.equals("GET") && !method.equals("POST")
                && !method.equals("PUT") && !method.equals("DELETE")) {
            System.out.println("Unsupported method: " + method);
            printUsage();
            return;
        }

        if (!path.startsWith("/")) {
            System.out.println("Path must start with '/': " + path);
            return;
        }

        boolean needsBody = method.equals("POST") || method.equals("PUT");
        if (needsBody && body == null) {
            System.out.println(method + " needs content as the last argument.");
            printUsage();
            return;
        }
        if (!needsBody && body != null) {
            System.out.println(method + " does not take content.");
            printUsage();
            return;
        }

        byte[] bodyBytes = (body == null) ? null : body.getBytes(StandardCharsets.UTF_8);
        byte[] header = buildHeader(method, path, host, port, bodyBytes);

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);

            OutputStream out = socket.getOutputStream();
            out.write(header);
            if (bodyBytes != null) {
                out.write(bodyBytes);
            }
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            readResponse(in);

        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + host);
        } catch (ConnectException e) {
            System.out.println("Could not connect to " + host + ":" + port);
        } catch (SocketTimeoutException e) {
            System.out.println("Timed out waiting for the server.");
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    // Reads the status line, the headers up to the blank line, and then the body.
    private static void readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("server closed the connection without a response");
        }

        // e.g. "HTTP/1.1 404 Not Found" -> version, code, reason (reason may contain spaces)
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/") || !parts[1].matches("\\d{3}")) {
            throw new IOException("malformed status line: " + statusLine);
        }
        String code = parts[1];
        String reason = (parts.length == 3) ? parts[2] : "";

        System.out.println("Status: " + code + " " + reason);
        System.out.println("Headers:");

        int contentLength = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            System.out.println("  " + line);

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed header: " + line);
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            if (name.equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IOException("invalid Content-Length: " + value);
                }
                if (contentLength < 0) {
                    throw new IOException("invalid Content-Length: " + value);
                }
            }
        }
        if (line == null) {
            throw new IOException("connection closed before the end of the headers");
        }

        // Read exactly Content-Length bytes. Without Content-Length, the server
        // sends Connection: close, so the body ends when the connection closes.
        byte[] body;
        if (contentLength >= 0) {
            body = in.readNBytes(contentLength);
            if (body.length < contentLength) {
                throw new IOException("connection closed after " + body.length
                        + " of " + contentLength + " body bytes");
            }
        } else {
            body = in.readAllBytes();
        }

        if (body.length == 0) {
            System.out.println("Body: (empty)");
        } else {
            System.out.println("Body (" + body.length + " bytes):");
            System.out.println(new String(body, StandardCharsets.UTF_8));
        }
    }

    // Reads one line ending in "\n" and drops the "\r" before it.
    // Returns null if the stream ends before any byte is read.
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            buffer.write(b);
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        String line = buffer.toString(StandardCharsets.UTF_8);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    // Request line and headers, each line ending in CRLF, then a blank line.
    // Content-Length is the body size in bytes, not characters.
    private static byte[] buildHeader(String method, String path, String host, int port, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (body != null) {
            sb.append("Content-Type: application/json\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void printUsage() {
        System.out.println("Usage: java HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [content]");
        System.out.println("  content is required for POST and PUT, e.g.");
        System.out.println("  java HttpClient localhost 8080 POST /users/alice.json '{\"name\":\"Alice\"}'");
    }
}
