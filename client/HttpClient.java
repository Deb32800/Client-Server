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

            // For now the raw response is printed. Parsing the status line,
            // headers and body is the next step.
            InputStream in = socket.getInputStream();
            byte[] response = in.readAllBytes();
            System.out.print(new String(response, StandardCharsets.UTF_8));

        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + host);
        } catch (ConnectException e) {
            System.out.println("Could not connect to " + host + ":" + port);
        } catch (SocketTimeoutException e) {
            System.out.println("Timed out waiting for the server.");
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
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
