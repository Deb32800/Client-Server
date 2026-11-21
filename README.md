# HTTP Client and Concurrent Server

Network Systems midterm project: a basic HTTP/1.1 client and a concurrent HTTP server in Java, using only `Socket` and `ServerSocket`.

## Structure

```
README.md
report.md
client/
    HttpClient.java
server/
    HttpServer.java
    HttpRequestHandler.java
```

## Status

| Part | Status |
|---|---|
| Part B: Client (`client/HttpClient.java`) | Done |
| Part A: Server (`server/`) | Not started |

## Client

### Compile

From the project folder:

```bash
javac -d build client/HttpClient.java
```

The `.class` files go into `build/`. Don't commit that folder.

### Run

```bash
java -cp build HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [content]
```

- `method` can be lowercase too; it is sent in uppercase.
- `path` must start with `/`.
- `content` is required for POST and PUT and not allowed for GET and DELETE. Put JSON in single quotes so the shell leaves it alone.

### Examples

```bash
java -cp build HttpClient localhost 8080 POST /users/alice.json '{"name":"Alice","age":21,"major":"CS"}'
java -cp build HttpClient localhost 8080 GET /users/alice.json
java -cp build HttpClient localhost 8080 PUT /users/alice.json '{"name":"Alice","age":22,"major":"CS"}'
java -cp build HttpClient localhost 8080 DELETE /users/alice.json
```

### What the client sends

The client builds the request itself. Every line ends in `\r\n`, and a blank line separates the headers from the body. For the POST above:

```
POST /users/alice.json HTTP/1.1
Host: localhost:8080
Content-Type: application/json
Content-Length: 38
Connection: close

{"name":"Alice","age":21,"major":"CS"}
```

`Content-Length` is the body size in UTF-8 bytes, not Java characters. GET and DELETE send no `Content-Type` or `Content-Length`.

### What the client prints

It parses the response and prints the status code and reason phrase, the headers, and the body:

```
Status: 200 OK
Headers:
  Content-Type: application/json
  Content-Length: 25
  Connection: close
Body (25 bytes):
{"name":"Alice","age":21}
```

When there is no body, for example with a 201 or 204:

```
Status: 204 No Content
Headers:
  Content-Length: 0
  Connection: close
Body: (empty)
```

### Errors

| Situation | Output |
|---|---|
| Nothing is listening on the port | `Could not connect to localhost:8080` |
| Unknown host name | `Unknown host: <host>` |
| No reply within 10 seconds | `Timed out waiting for the server.` |
| Server closes before sending anything | `Error: server closed the connection without a response` |
| Status line is not `HTTP/x.x <3 digits> ...` | `Error: malformed status line: ...` |
| Body shorter than `Content-Length` | `Error: connection closed after N of M body bytes` |
| Wrong arguments | Message and usage line, nothing is sent |

## Notes for the server

What the client expects from the server:

- A status line like `HTTP/1.1 201 Created`, followed by headers, with every line ending in `\r\n`, then a blank line.
- `Content-Length` set to the exact body size in bytes, and `Content-Length: 0` when there is no body. The client reads exactly that many bytes. If the header is missing, it reads until the connection closes.
- The connection closed after the response. The client always sends `Connection: close`.
- POST and PUT bodies always arrive with `Content-Type: application/json` and a `Content-Length`. The server has to read exactly `Content-Length` bytes, because one `read()` may not return the whole body.

## Testing

The client has been tested against:

- Fake servers made with `nc`, to check the exact request bytes and how the client handles a normal body, a UTF-8 body, an empty 204, a missing `Content-Length`, a body cut off early, a malformed status line and an empty reply.
- Python's built-in server (`python3 -m http.server`): GET returned 200 with the correct body, and a missing file returned 404.

It has **not yet been tested against our own server**. When the server is ready, run this sequence. The expected status is in the comment:

```bash
java -cp build HttpClient localhost 8080 POST   /users/alice.json '{"name":"Alice","age":21}'   # 201 Created
java -cp build HttpClient localhost 8080 POST   /users/alice.json '{"name":"Alice","age":21}'   # 409 Conflict
java -cp build HttpClient localhost 8080 GET    /users/alice.json                               # 200 OK, same body
java -cp build HttpClient localhost 8080 PUT    /users/alice.json '{"name":"Alice","age":22}'   # 200 or 204
java -cp build HttpClient localhost 8080 GET    /users/alice.json                               # 200 OK, new body
java -cp build HttpClient localhost 8080 DELETE /users/alice.json                               # 204 No Content
java -cp build HttpClient localhost 8080 GET    /users/alice.json                               # 404 Not Found
java -cp build HttpClient localhost 8080 PUT    /users/bob.json   '{"name":"Bob"}'              # 404 Not Found
```

## Known limitations (client)

- The path is sent exactly as typed and is not percent-encoded. A path with spaces has to be written with `%20`.
- `Content-Type` is always `application/json`, since the assignment only uses JSON bodies.
