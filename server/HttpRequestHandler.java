/*
* Network Systems Midterm Project
*
* Group: M6
* Authors:- 
* Ghosh Deb Kumar                 is0806fe@ed.ritsumei.ac.jp
* PAN LUCHENG                     is0857ki@ed.ritsumei.ac.jp
* Alina Shige-Hird                is0858fk@ed.ritsumei.ac.jp
* Alessandro Enriqco Putra Bayu   is0804hf@ed.ritsumei.ac.jp
*
* Runnable class used by HttpServer. Each object runs in its own thread and handles one HTTP request: it reads the request line, the headers and the
* body, does GET, POST, PUT or DELETE on a file in the storage directory, then it sends back one response and closes the connection.
*/

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

public class HttpRequestHandler implements Runnable {
    private static final Object STORAGE_LOCK = new Object();

    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final Socket socket;
    private final Path root;  // the storage directory (server_data)
    private String firstLine; 

    public HttpRequestHandler(Socket socket, Path root) {
        this.socket = socket;
    
        this.root = root.toAbsolutePath().normalize();
    }

    // run() is executed by the new thread after start() is called.
    @Override
    public void run() {
        // try-with-resources closes the connection after the response, so there is only one request per connection.
        try (Socket s = socket) {
            s.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            try {
                handleRequest(in, out);
            } catch (BadRequestException e) {
                send(out, 400, "Bad Request", null, null);
            } catch (SocketTimeoutException e) {
                send(out, 408, "Request Timeout", null, null);
            } catch (Exception e) {
                
                send(out, 500, "Internal Server Error", null, null);
            }
        } catch (IOException e) {
            System.out.println("[" + Thread.currentThread().getName() + "] connection error: " + e.getMessage());
        }
    }

    // Reads the request line, the headers and the body, then does the operation.
    private void handleRequest(InputStream in, OutputStream out) throws IOException {
   
        String requestLine = readLine(in);
        firstLine = requestLine;
        if (requestLine == null) {
            throw new BadRequestException();
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) {
            throw new BadRequestException();
        }
        String method = parts[0];
        String path = parts[1];

        // We read the header lines until the empty line. Header names are not case sensitive, so we save them in lowercase.
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


        // Content-Length is the size of the body in bytes
        int length = 0;

        if (headers.containsKey("content-length")) {
            try {
                length = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException e) {
                throw new BadRequestException();

            }
            if (length < 0 || length > MAX_BODY_BYTES) {

                throw new BadRequestException();
            }
        }
        byte[] body = readExactly(in, length);

        if (!method.equals("GET") && !method.equals("POST")
                && !method.equals("PUT") && !method.equals("DELETE")) {
            send(out, 405, "Method Not Allowed", null, null);
            return;
        }
        Path file = resolvePath(path);

        synchronized (STORAGE_LOCK) {
            if (Files.isDirectory(file)) {
                // A request must target a file, not a directory.
                throw new BadRequestException();
            }
            switch (method) {
                case "GET":    doGet(file, out); break;
                case "POST":   doPost(file, body, out); break;
                case "PUT":    doPut(file, body, out); break;
                case "DELETE": doDelete(file, out); break;
            }
        }
    }

    // Reads one line and returns it without the CRLF ("\r\n") at the end. We read one byte at a time, so we do not read any byte of the body by mistake.
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

    
    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int total = 0;
        while (total < length) {
            int n = in.read(body, total, length - total);
            if (n == -1) {
                // The client closed the connection before it sent the whole body.
                throw new BadRequestException();
            }
            total += n;
        }
        return body;
    }

    private Path resolvePath(String path) throws BadRequestException {
        if (!path.startsWith("/")) {
            throw new BadRequestException();
        }
        if (path.endsWith("/")) {
            throw new BadRequestException();
        }
        Path file;
        try {

            file = root.resolve(path.substring(1)).normalize();

        } catch (InvalidPathException e) {

            // The name cannot be used as a file name.
            throw new BadRequestException();
        }
        if (!file.startsWith(root) || file.equals(root)) {
            throw new BadRequestException();
        }
        return file;
    }

    
    private void doGet(Path file, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        String contentType = file.toString().endsWith(".json") ? "application/json" : "text/plain";
        send(out, 200, "OK", contentType, Files.readAllBytes(file));
    }

    // POST creates a new file with the request body as its content.
    private void doPost(Path file, byte[] body, OutputStream out) throws IOException {
        if (Files.exists(file)) {
            send(out, 409, "Conflict", null, null);
            return;
        }
        
        for (Path dir = file.getParent(); !dir.equals(root); dir = dir.getParent()) {
            if (Files.exists(dir) && !Files.isDirectory(dir)) {
                throw new BadRequestException();
            }
        }
        
        Files.createDirectories(file.getParent());
        Files.write(file, body, StandardOpenOption.CREATE_NEW);
        send(out, 201, "Created", null, null);
    }

    // PUT replaces the content of a file with the request body. 
    private void doPut(Path file, byte[] body, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        Files.write(file, body);
        send(out, 204, "No Content", null, null);
    }

    // DELETE removes an existing file.
    private void doDelete(Path file, OutputStream out) throws IOException {
        if (!Files.exists(file)) {
            send(out, 404, "Not Found", null, null);
            return;
        }
        Files.delete(file);
        send(out, 204, "No Content", null, null);
    }

    private void send(OutputStream out, int status, String reason, String contentType, byte[] body)
            throws IOException {
        if (body == null) {
            body = new byte[0];
        }
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n";

        if (contentType != null) {

            head += "Content-Type: " + contentType + "\r\n";
        }
        // A 405 response must say which methods are allowed.
        if (status == 405) {
            head += "Allow: GET, POST, PUT, DELETE\r\n";
        }
        head += "Content-Length: " + body.length + "\r\n";
      
        head += "Connection: close\r\n";

        head += "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();

        // We print one line for each request, e.g. "[Thread-3] POST /users/alice.json HTTP/1.1 -> 201 Created"
        System.out.println("[" + Thread.currentThread().getName() + "] "
                + (firstLine != null ? firstLine : "(no request line)") + " -> " + status + " " + reason);
    }

    // We throw this for a malformed request, a bad Content-Length or a bad path. run() catches it and sends 400 Bad Request.
    private static class BadRequestException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
