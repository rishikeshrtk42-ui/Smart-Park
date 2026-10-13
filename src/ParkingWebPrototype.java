import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParkingWebPrototype {

    private static final int PORT = getPort();
    private static final int TOTAL_SLOTS = 12;
    private static final String ADMIN_PASSKEY = getAdminPasskey();

    private static int getPort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.trim().isEmpty()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 8080;
    }

    private static String getAdminPasskey() {
        String passkeyEnv = System.getenv("ADMIN_PASSKEY");
        if (passkeyEnv == null || passkeyEnv.trim().isEmpty()) {
            System.err.println("FATAL ERROR: Environment variable 'ADMIN_PASSKEY' is required and must not be empty.");
            System.err.println("Startup aborted. Please configure ADMIN_PASSKEY before running the application.");
            System.exit(1);
            return null;
        }
        return passkeyEnv.trim();
    }

    // --- Billing Rules ---
    private static final int FREE_GRACE_PERIOD_SECONDS = 10 * 60; // 10 minutes free
    private static final double RATE_PER_30_MINS = 10.00;        // ₹10 / 30 mins
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Regex pattern: Group 1 (KL), Group 2 (01-86), Group 3 (A-Z series), Group 4 (4 digits)
    private static final Pattern PLATE_PATTERN = Pattern.compile("^([A-Z]{2})\\s*(\\d{2})\\s*([A-Z]{1,3})\\s*(\\d{4})$");

    static class Slot {
        int id;
        boolean occupied;
        String vehicleNo;
        String vehicleType;
        LocalDateTime entryTime;

        Slot(int id) {
            this.id = id;
            this.occupied = false;
        }
    }

    private static final List<Slot> slots = new ArrayList<Slot>();
    private static final List<String> logs = new ArrayList<String>();

    public static void main(String[] args) throws IOException {
        for (int i = 1; i <= TOTAL_SLOTS; i++) {
            slots.add(new Slot(i));
        }
        logs.add("[" + LocalDateTime.now().format(TIME_FMT) + "] Server started. Validating format: KL 07 CC 2026.");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", new StaticHandler());
        server.createContext("/api/slots", new SlotsApiHandler());
        server.createContext("/api/checkin", new CheckInApiHandler());
        server.createContext("/api/checkout", new CheckOutApiHandler());
        server.createContext("/api/logs", new LogsApiHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("===============================================================");
        System.out.println("  Smart Park Server Running!");
        System.out.println("  Kerala Plate Validator (KL 01-86 [A-Z]+ 0001-9999)");
        System.out.println("  Admin Logs Protected: [CONFIGURED]");
        System.out.println("  Listening on: 0.0.0.0:" + PORT);
        System.out.println("===============================================================");
    }

    // --- Plate Verification Logic ---
    private static String validateAndNormalizePlate(String input) {
        if (input == null) return "Vehicle number is required.";
        String clean = input.trim().toUpperCase();

        Matcher m = PLATE_PATTERN.matcher(clean);
        if (!m.matches()) {
            String rawWithoutSpaces = clean.replaceAll("\\s+", "");
            if (!rawWithoutSpaces.startsWith("KL")) {
                return "Invalid Plate: Must start with 'KL'.";
            }
            if (rawWithoutSpaces.length() >= 4) {
                String rtoPart = rawWithoutSpaces.substring(2, 4);
                if (!rtoPart.matches("\\d{2}")) {
                    return "Invalid RTO Code: 3rd and 4th characters must be a two-digit code (e.g., 07).";
                }
            }
            return "Invalid Format! Must match pattern: KL 07 CC 2026 (e.g., KL <01-86> <A-Z> <4-digits>).";
        }

        String state = m.group(1);
        String rtoStr = m.group(2);
        String series = m.group(3);
        String numberStr = m.group(4);

        if (!"KL".equals(state)) {
            return "Invalid State Code: Must begin with 'KL'.";
        }

        int rto = Integer.parseInt(rtoStr);
        if (rto < 1 || rto > 86) {
            return "Invalid District Code: '" + rtoStr + "' is invalid. Must range from 01 to 86.";
        }

        if (!series.matches("^[A-Z]+$")) {
            return "Invalid Series: Series characters must be letters (A-Z) only.";
        }

        if ("0000".equals(numberStr)) {
            return "Invalid Number: '0000' is not permitted as a registration number.";
        }

        return null; // null indicates validation passed
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response = getHtmlContent().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    static class SlotsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder json = new StringBuilder("[");
            synchronized (slots) {
                for (int i = 0; i < slots.size(); i++) {
                    Slot s = slots.get(i);
                    json.append(String.format(Locale.US,
                        "{\"id\":%d,\"occupied\":%b,\"vehicleNo\":\"%s\",\"vehicleType\":\"%s\",\"entryTime\":\"%s\"}",
                        s.id,
                        s.occupied,
                        s.vehicleNo != null ? s.vehicleNo : "",
                        s.vehicleType != null ? s.vehicleType : "",
                        s.entryTime != null ? s.entryTime.format(TIME_FMT) : ""
                    ));
                    if (i < slots.size() - 1) json.append(",");
                }
            }
            json.append("]");
            sendJsonResponse(exchange, 200, json.toString());
        }
    }

    static class CheckInApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            Map<String, String> data = parseJsonSimple(body);
            String rawReg = data.get("regNo");
            String type = data.get("vehicleType");

            String validationError = validateAndNormalizePlate(rawReg);
            if (validationError != null) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"" + validationError + "\"}");
                return;
            }

            String reg = rawReg.trim().toUpperCase().replaceAll("\\s+", " ");
            Matcher m = PLATE_PATTERN.matcher(reg);
            if (m.matches()) {
                reg = m.group(1) + " " + m.group(2) + " " + m.group(3) + " " + m.group(4);
            }

            synchronized (slots) {
                for (Slot s : slots) {
                    if (s.occupied && reg.equalsIgnoreCase(s.vehicleNo)) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Vehicle " + reg + " is already parked in Slot " + s.id + ".\"}");
                        return;
                    }
                }

                for (Slot s : slots) {
                    if (!s.occupied) {
                        s.occupied = true;
                        s.vehicleNo = reg;
                        s.vehicleType = (type != null && !type.isEmpty()) ? type : "4-Wheeler";
                        s.entryTime = LocalDateTime.now();

                        String logEntry = "[" + s.entryTime.format(TIME_FMT) + "] CHECK-IN: " + reg + " (" + s.vehicleType + ") -> Slot " + s.id;
                        synchronized (logs) { logs.add(logEntry); }

                        sendJsonResponse(exchange, 200, String.format(Locale.US,
                            "{\"success\":true,\"slotId\":%d,\"regNo\":\"%s\",\"entryTime\":\"%s\"}",
                            s.id, s.vehicleNo, s.entryTime.format(TIME_FMT)
                        ));
                        return;
                    }
                }
            }

            sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Parking facility is completely full!\"}");
        }
    }

    static class CheckOutApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = readRequestBody(exchange);
            Map<String, String> data = parseJsonSimple(body);
            String slotStr = data.get("slotId");

            int slotId;
            try {
                slotId = Integer.parseInt(slotStr);
                if (slotId < 1 || slotId > TOTAL_SLOTS) throw new NumberFormatException();
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Invalid Slot Number.\"}");
                return;
            }

            synchronized (slots) {
                Slot s = slots.get(slotId - 1);
                if (!s.occupied) {
                    sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Slot " + slotId + " is already vacant.\"}");
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                long seconds = Duration.between(s.entryTime, now).getSeconds();

                double fee = 0.0;
                long billableMinutes = 0;
                String billingRemark;

                if (seconds <= FREE_GRACE_PERIOD_SECONDS) {
                    fee = 0.00;
                    billingRemark = "Within 10-min grace period (₹0)";
                } else {
                    billableMinutes = (long) Math.ceil(seconds / 60.0);
                    long blocks = (long) Math.ceil(billableMinutes / 30.0);
                    fee = blocks * RATE_PER_30_MINS;
                    billingRemark = blocks + " block(s) of 30 mins";
                }

                String invoiceJson = String.format(Locale.US,
                    "{\"success\":true,\"slotId\":%d,\"vehicleNo\":\"%s\",\"vehicleType\":\"%s\",\"entryTime\":\"%s\",\"exitTime\":\"%s\",\"durationSec\":%d,\"billableMin\":%d,\"remark\":\"%s\",\"fee\":%.2f}",
                    s.id, s.vehicleNo, s.vehicleType, s.entryTime.format(TIME_FMT), now.format(TIME_FMT), seconds, billableMinutes, billingRemark, fee
                );

                String logEntry = String.format(Locale.US, "[%s] CHECK-OUT: %s left Slot %d | Duration: %ds | Billed: ₹%.2f (%s)",
                    now.format(TIME_FMT), s.vehicleNo, s.id, seconds, fee, billingRemark);
                synchronized (logs) { logs.add(logEntry); }

                s.occupied = false;
                s.vehicleNo = null;
                s.vehicleType = null;
                s.entryTime = null;

                sendJsonResponse(exchange, 200, invoiceJson);
            }
        }
    }

    // --- Protected Logs API Endpoint ---
    static class LogsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Check Authorization passkey from query param or header
            String query = exchange.getRequestURI().getQuery();
            String passkeyProvided = null;
            if (query != null && query.contains("key=")) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "key".equals(pair[0])) {
                        passkeyProvided = pair[1];
                    }
                }
            }

            if (!ADMIN_PASSKEY.equals(passkeyProvided)) {
                sendJsonResponse(exchange, 401, "{\"success\":false,\"message\":\"Unauthorized: Invalid Admin Passkey!\"}");
                return;
            }

            StringBuilder json = new StringBuilder("[");
            synchronized (logs) {
                for (int i = logs.size() - 1; i >= 0; i--) {
                    json.append("\"").append(logs.get(i).replace("\"", "\\\"")).append("\"");
                    if (i > 0) json.append(",");
                }
            }
            json.append("]");
            sendJsonResponse(exchange, 200, json.toString());
        }
    }

    private static void sendJsonResponse(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String readRequestBody(HttpExchange ex) throws IOException {
        InputStreamReader isr = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private static Map<String, String> parseJsonSimple(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null || body.isEmpty()) return map;
        String clean = body.replaceAll("[{}\"]", "");
        for (String pair : clean.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

    private static String getHtmlContent() {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <title>Smart Park</title>\n" +
            "  <style>\n" +
            "    :root { --bg: #0f172a; --card: #1e293b; --border: #334155; --accent: #3b82f6; --success: #10b981; --danger: #ef4444; --text: #f8fafc; --text-muted: #94a3b8; }\n" +
            "    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }\n" +
            "    body { background: var(--bg); color: var(--text); padding: 24px; display: flex; justify-content: center; }\n" +
            "    .container { width: 100%; max-width: 1050px; display: flex; flex-direction: column; gap: 20px; }\n" +
            "    header { background: var(--card); border: 1px solid var(--border); border-radius: 12px; display: flex; justify-content: space-between; align-items: center; }\n" +
            "    .badge { background: #0284c7; padding: 6px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; }\n" +
            "    .stats-bar { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }\n" +
            "    .stat-box { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 16px; text-align: center; }\n" +
            "    .stat-box .num { font-size: 26px; font-weight: 800; margin-top: 4px; }\n" +
            "    .text-green { color: var(--success); } .text-red { color: var(--danger); } .text-blue { color: var(--accent); }\n" +
            "    .main-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 20px; }\n" +
            "    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; }\n" +
            "    .slots-matrix { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }\n" +
            "    .slot-tile { background: #0f172a; border: 2px solid var(--border); border-radius: 8px; padding: 14px 8px; text-align: center; cursor: pointer; transition: 0.2s; }\n" +
            "    .slot-tile:hover { transform: translateY(-2px); }\n" +
            "    .slot-tile.available { border-color: var(--success); }\n" +
            "    .slot-tile.occupied { border-color: var(--danger); background: rgba(239, 68, 68, 0.08); }\n" +
            "    .slot-id { font-size: 13px; font-weight: 700; color: var(--text-muted); }\n" +
            "    .slot-status { font-size: 13px; font-weight: 800; margin-top: 4px; }\n" +
            "    .available .slot-status { color: var(--success); }\n" +
            "    .occupied .slot-status { color: var(--danger); }\n" +
            "    .slot-meta { font-size: 11px; color: var(--text-muted); margin-top: 4px; }\n" +
            "    .form-group { margin-bottom: 14px; }\n" +
            "    label { display: block; font-size: 13px; color: var(--text-muted); margin-bottom: 6px; }\n" +
            "    input, select { width: 100%; padding: 10px; background: #0f172a; border: 1px solid var(--border); border-radius: 6px; color: #fff; font-size: 14px; }\n" +
            "    .hint { font-size: 11px; color: #38bdf8; margin-top: 4px; display: block; }\n" +
            "    button { width: 100%; padding: 10px; border-radius: 6px; border: none; font-size: 14px; font-weight: 700; cursor: pointer; }\n" +
            "    .btn-blue { background: var(--accent); color: #fff; }\n" +
            "    .btn-red { background: var(--danger); color: #fff; margin-top: 10px; }\n" +
            "    .btn-lock { background: #334155; color: #f8fafc; padding: 8px 16px; border-radius: 6px; width: auto; font-size: 12px; display: inline-block; }\n" +
            "    .logs-box { background: #0f172a; border: 1px solid var(--border); border-radius: 8px; padding: 12px; height: 140px; overflow-y: auto; font-family: monospace; font-size: 12px; }\n" +
            "    .locked-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 10px; color: var(--text-muted); text-align: center; }\n" +
            "    .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: none; justify-content: center; align-items: center; z-index: 100; }\n" +
            "    .modal-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 24px; width: 390px; text-align: center; }\n" +
            "    .modal-receipt { background: #0f172a; border: 1px dashed var(--border); border-radius: 6px; padding: 14px; text-align: left; font-size: 13px; font-family: monospace; margin: 12px 0 16px 0; }\n" +
            "    .receipt-row { display: flex; justify-content: space-between; margin-bottom: 6px; }\n" +
            "    .receipt-total { border-top: 1px solid var(--border); padding-top: 8px; font-weight: 700; color: #fff; font-size: 15px; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"container\">\n" +
            "    <header>\n" +
            "      <div><h2>Smart Park</h2></div>\n" +
            "      <span class=\"badge\">Tariff: ₹10 / 30 mins | First 10 mins FREE</span>\n" +
            "    </header>\n" +
            "    <div class=\"stats-bar\">\n" +
            "      <div class=\"stat-box\"><span style=\"color:var(--text-muted)\">Total Slots</span><div class=\"num text-blue\" id=\"totalCount\">12</div></div>\n" +
            "      <div class=\"stat-box\"><span style=\"color:var(--text-muted)\">Available</span><div class=\"num text-green\" id=\"availableCount\">0</div></div>\n" +
            "      <div class=\"stat-box\"><span style=\"color:var(--text-muted)\">Occupied</span><div class=\"num text-red\" id=\"occupiedCount\">0</div></div>\n" +
            "    </div>\n" +
            "    <div class=\"main-grid\">\n" +
            "      <div class=\"card\">\n" +
            "        <h3 style=\"color:var(--text-muted);font-size:14px;margin-bottom:14px;\">LIVE SLOT MATRIX (Click slot to select)</h3>\n" +
            "        <div class=\"slots-matrix\" id=\"slotsGrid\"></div>\n" +
            "      </div>\n" +
            "      <div class=\"card\">\n" +
            "        <h3 style=\"color:var(--text-muted);font-size:14px;margin-bottom:14px;\">GATE OPERATIONS</h3>\n" +
            "        <div class=\"form-group\">\n" +
            "          <label>Vehicle Plate No:</label>\n" +
            "          <input type=\"text\" id=\"regNo\" placeholder=\"e.g. KL 07 CC 2026\" autocomplete=\"off\">\n" +
            "          <span class=\"hint\">Format: KL [01-86] [Letters] [0001-9999]</span>\n" +
            "        </div>\n" +
            "        <div class=\"form-group\">\n" +
            "          <label>Category:</label>\n" +
            "          <select id=\"vehicleType\">\n" +
            "            <option value=\"4-Wheeler\">4-Wheeler (Car/SUV)</option>\n" +
            "            <option value=\"2-Wheeler\">2-Wheeler</option>\n" +
            "          </select>\n" +
            "        </div>\n" +
            "        <button class=\"btn-blue\" onclick=\"checkIn()\">Check-In (Auto Allocate)</button>\n" +
            "        <hr style=\"border:0;border-top:1px solid var(--border);margin:18px 0;\">\n" +
            "        <div class=\"form-group\"><label>Slot to Exit:</label><input type=\"number\" id=\"selectedSlot\" placeholder=\"Slot No (1-12)\" min=\"1\" max=\"12\"></div>\n" +
            "        <button class=\"btn-red\" onclick=\"checkOut()\">Check-Out & Generate Invoice</button>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "    <div class=\"card\">\n" +
            "      <div style=\"display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;\">\n" +
            "        <h3 style=\"color:var(--text-muted);font-size:14px;\">TRANSACTION & AUDIT LOGS (ADMIN ONLY)</h3>\n" +
            "        <button class=\"btn-lock\" id=\"lockToggleBtn\" onclick=\"promptAdminAccess()\">Unlock Admin View</button>\n" +
            "      </div>\n" +
            "      <div class=\"logs-box\" id=\"logsBox\">\n" +
            "        <div class=\"locked-state\">\n" +
            "          <span>🔒 Audit log is restricted to authorized administrators.</span>\n" +
            "        </div>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "  <div class=\"modal-backdrop\" id=\"invoiceModal\">\n" +
            "    <div class=\"modal-card\">\n" +
            "      <h2 style=\"color:var(--success);font-size:18px;\">Checkout Complete</h2>\n" +
            "      <div class=\"modal-receipt\" id=\"receiptContent\"></div>\n" +
            "      <button class=\"btn-blue\" onclick=\"closeModal()\">Close</button>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "  <div class=\"modal-backdrop\" id=\"adminModal\">\n" +
            "    <div class=\"modal-card\">\n" +
            "      <h2 style=\"color:var(--accent);font-size:18px;margin-bottom:12px;\">Admin Authentication</h2>\n" +
            "      <div class=\"form-group\">\n" +
            "        <label>Enter Admin Passkey:</label>\n" +
            "        <input type=\"password\" id=\"adminPasskeyInput\" placeholder=\"Passkey\">\n" +
            "      </div>\n" +
            "      <div style=\"display:flex; gap:10px; margin-top:10px;\">\n" +
            "        <button class=\"btn-blue\" onclick=\"authenticateAdmin()\">Authenticate</button>\n" +
            "        <button class=\"btn-red\" style=\"margin-top:0;\" onclick=\"closeAdminModal()\">Cancel</button>\n" +
            "      </div>\n" +
            "    </div>\n" +
            "  </div>\n" +
            "  <script>\n" +
            "    let adminPasskey = null;\n" +
            "    function validatePlateClient(plate) {\n" +
            "      const clean = plate.trim().toUpperCase();\n" +
            "      const regex = /^([A-Z]{2})\\s*(\\d{2})\\s*([A-Z]{1,3})\\s*(\\d{4})$/;\n" +
            "      const match = clean.match(regex);\n" +
            "      if (!match) return 'Invalid format! Must follow: KL 07 CC 2026';\n" +
            "      const [_, state, rto, series, num] = match;\n" +
            "      if (state !== 'KL') return 'Error: Registration must start with KL.';\n" +
            "      const rtoVal = parseInt(rto, 10);\n" +
            "      if (rtoVal < 1 || rtoVal > 86) return 'Error: District RTO code must be between 01 and 86.';\n" +
            "      if (num === '0000') return 'Error: 0000 is not allowed as a vehicle number.';\n" +
            "      return null;\n" +
            "    }\n" +
            "    async function fetchState() {\n" +
            "      const resSlots = await fetch('/api/slots');\n" +
            "      const slots = await resSlots.json();\n" +
            "      let available = 0, occupied = 0;\n" +
            "      const grid = document.getElementById('slotsGrid');\n" +
            "      grid.innerHTML = '';\n" +
            "      slots.forEach(s => {\n" +
            "        if (s.occupied) occupied++; else available++;\n" +
            "        const div = document.createElement('div');\n" +
            "        div.className = 'slot-tile ' + (s.occupied ? 'occupied' : 'available');\n" +
            "        div.onclick = () => { document.getElementById('selectedSlot').value = s.id; };\n" +
            "        div.innerHTML = '<div class=\"slot-id\">SLOT ' + s.id + '</div>' +\n" +
            "                        '<div class=\"slot-status\">' + (s.occupied ? s.vehicleNo : 'OPEN') + '</div>' +\n" +
            "                        (s.occupied ? '<div class=\"slot-meta\">' + s.vehicleType + '<br>In: ' + s.entryTime + '</div>' : '<div class=\"slot-meta\">Vacant</div>');\n" +
            "        grid.appendChild(div);\n" +
            "      });\n" +
            "      document.getElementById('availableCount').innerText = available;\n" +
            "      document.getElementById('occupiedCount').innerText = occupied;\n" +
            "      if (adminPasskey) fetchLogs();\n" +
            "    }\n" +
            "    async function fetchLogs() {\n" +
            "      if (!adminPasskey) return;\n" +
            "      const resLogs = await fetch('/api/logs?key=' + encodeURIComponent(adminPasskey));\n" +
            "      if (resLogs.status === 401) {\n" +
            "        alert('Admin Session Expired or Invalid Passkey.');\n" +
            "        lockAdmin();\n" +
            "        return;\n" +
            "      }\n" +
            "      const logs = await resLogs.json();\n" +
            "      document.getElementById('logsBox').innerHTML = logs.map(l => '<div>' + l + '</div>').join('');\n" +
            "    }\n" +
            "    function promptAdminAccess() {\n" +
            "      if (adminPasskey) { lockAdmin(); return; }\n" +
            "      document.getElementById('adminPasskeyInput').value = '';\n" +
            "      document.getElementById('adminModal').style.display = 'flex';\n" +
            "    }\n" +
            "    function closeAdminModal() {\n" +
            "      document.getElementById('adminModal').style.display = 'none';\n" +
            "    }\n" +
            "    async function authenticateAdmin() {\n" +
            "      const key = document.getElementById('adminPasskeyInput').value.trim();\n" +
            "      if (!key) return alert('Please enter passkey.');\n" +
            "      const res = await fetch('/api/logs?key=' + encodeURIComponent(key));\n" +
            "      if (res.status === 200) {\n" +
            "        adminPasskey = key;\n" +
            "        document.getElementById('lockToggleBtn').innerText = 'Lock Admin View';\n" +
            "        closeAdminModal();\n" +
            "        fetchLogs();\n" +
            "      } else {\n" +
            "        alert('Incorrect Admin Passkey!');\n" +
            "      }\n" +
            "    }\n" +
            "    function lockAdmin() {\n" +
            "      adminPasskey = null;\n" +
            "      document.getElementById('lockToggleBtn').innerText = 'Unlock Admin View';\n" +
            "      document.getElementById('logsBox').innerHTML = '<div class=\"locked-state\"><span>🔒 Audit log is restricted to authorized administrators.</span></div>';\n" +
            "    }\n" +
            "    async function checkIn() {\n" +
            "      const inputEl = document.getElementById('regNo');\n" +
            "      const regNo = inputEl.value.trim();\n" +
            "      const vehicleType = document.getElementById('vehicleType').value;\n" +
            "      if (!regNo) return alert('Please enter vehicle registration number.');\n" +
            "      const clientErr = validatePlateClient(regNo);\n" +
            "      if (clientErr) return alert(clientErr);\n" +
            "      const res = await fetch('/api/checkin', {\n" +
            "        method: 'POST',\n" +
            "        headers: { 'Content-Type': 'application/json' },\n" +
            "        body: JSON.stringify({ regNo, vehicleType })\n" +
            "      });\n" +
            "      const data = await res.json();\n" +
            "      if (!data.success) alert(data.message);\n" +
            "      else inputEl.value = '';\n" +
            "      fetchState();\n" +
            "    }\n" +
            "    async function checkOut() {\n" +
            "      const slotId = document.getElementById('selectedSlot').value;\n" +
            "      if (!slotId) return alert('Select or enter a slot number.');\n" +
            "      const res = await fetch('/api/checkout', {\n" +
            "        method: 'POST',\n" +
            "        headers: { 'Content-Type': 'application/json' },\n" +
            "        body: JSON.stringify({ slotId })\n" +
            "      });\n" +
            "      const data = await res.json();\n" +
            "      if (!data.success) return alert(data.message);\n" +
            "      document.getElementById('receiptContent').innerHTML = \n" +
            "        '<div class=\"receipt-row\"><span>Slot Number:</span><span>Slot ' + data.slotId + '</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Vehicle Plate:</span><span>' + data.vehicleNo + '</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Category:</span><span>' + data.vehicleType + '</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Entry Time:</span><span>' + data.entryTime + '</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Exit Time:</span><span>' + data.exitTime + '</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Duration:</span><span>' + data.durationSec + 's</span></div>' +\n" +
            "        '<div class=\"receipt-row\"><span>Billing Basis:</span><span>' + data.remark + '</span></div>' +\n" +
            "        '<div class=\"receipt-row receipt-total\"><span>TOTAL BILLED:</span><span>₹' + data.fee.toFixed(2) + '</span></div>';\n" +
            "      document.getElementById('invoiceModal').style.display = 'flex';\n" +
            "      document.getElementById('selectedSlot').value = '';\n" +
            "    }\n" +
            "    function closeModal() {\n" +
            "      document.getElementById('invoiceModal').style.display = 'none';\n" +
            "      fetchState();\n" +
            "    }\n" +
            "    fetchState();\n" +
            "    setInterval(fetchState, 5000);\n" +
            "  </script>\n" +
            "</body>\n" +
            "</html>";
    }
}