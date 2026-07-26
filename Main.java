import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OWNSTYLE — website ung ho (donate) viet hoan toan bang Java.
 * Khong dung framework ngoai, khong dung file .html/.css/.js rieng:
 * toan bo giao dien duoc server sinh ra tu chuoi Java (text block).
 *
 * Chay:  java Main.java
 * Mo:    http://localhost:8080
 */
public class Main {

    static final int PORT = resolvePort();
    static int resolvePort() {
        String env = System.getenv("PORT");
        if (env != null) {
            try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        return 8080;
    }
    static final List<Donation> DONATIONS = new CopyOnWriteArrayList<>();
    static final Set<String> CONFIRMED_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final AtomicInteger ID_SEQ = new AtomicInteger(1000);

    // ====================================================================
    // CAU HINH TAI KHOAN NHAN TIEN — SUA LAI THANH THONG TIN THAT CUA BAN!
    // ====================================================================
    // Ma ngan hang theo chuan VietQR/Napas. Mot vai ma pho bien:
    // Vietcombank=970436  BIDV=970418  MB Bank=970422  Techcombank=970407
    // ACB=970416  TPBank=970423  VPBank=970432  MoMo(vi)=970454
    static final String BANK_BIN = "970436";
    static final String BANK_ACCOUNT_NO = "SO_TAI_KHOAN_CUA_BAN";
    static final String BANK_ACCOUNT_NAME = "NGUYEN VAN A"; // KHONG DAU, viet hoa

    // So dien thoai ZaloPay ca nhan de hien thi cung anh QR
    static final String ZALOPAY_PHONE = "09xxxxxxxx";
    // Anh QR ZaloPay: mo app ZaloPay > Vi > Nhan tien > luu anh QR, dat ten file
    // "zalopay-qr.png" va bo cung thu muc voi Main.java. Server se tu hien thi.
    static final String ZALOPAY_QR_FILE = "zalopay-qr.png";

    public static void main(String[] args) throws IOException {
        seedDemoData();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleHome);
        server.createContext("/donate", Main::handleDonatePage);
        server.createContext("/submit-donation", Main::handleSubmitDonation);
        server.createContext("/pay", Main::handlePayPage);
        server.createContext("/confirm-payment", Main::handleConfirmPayment);
        server.createContext("/thanks", Main::handleThanks);
        server.createContext("/supporters", Main::handleSupporters);
        server.createContext("/assets/style.css", Main::handleCss);
        server.createContext("/assets/zalopay-qr.png", Main::handleZaloQrImage);
        server.createContext("/favicon.ico", ex -> { ex.sendResponseHeaders(204, -1); });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("=======================================================");
        System.out.println(" OWNSTYLE dang chay tai: http://localhost:" + PORT);
        System.out.println(" Nhan Ctrl+C de dung server.");
        System.out.println("=======================================================");
    }

    // ---------------------------------------------------------------------
    // Du lieu
    // ---------------------------------------------------------------------

    record Donation(String id, String name, String message, long amount,
                     String tier, String method, ZonedDateTime time) {}

    static void seedDemoData() {
        addConfirmed(new Donation(nextId(), "Minh Anh", "Chuc trang ngay cang phat trien!", 500_000,
                tierFor(500_000), "Chuyen khoan ngan hang", ZonedDateTime.now().minusHours(5)));
        addConfirmed(new Donation(nextId(), "An danh", "Ung ho tinh than :)", 50_000,
                tierFor(50_000), "ZaloPay", ZonedDateTime.now().minusHours(2)));
        addConfirmed(new Donation(nextId(), "Gia Han", "Rat thich phong cach cua ban!", 2_000_000,
                tierFor(2_000_000), "Chuyen khoan ngan hang", ZonedDateTime.now().minusMinutes(30)));
    }

    static void addConfirmed(Donation d) {
        DONATIONS.add(d);
        CONFIRMED_IDS.add(d.id());
    }

    static boolean isConfirmed(Donation d) { return CONFIRMED_IDS.contains(d.id()); }

    static String nextId() { return "OS" + ID_SEQ.incrementAndGet(); }

    static String tierFor(long amount) {
        if (amount >= 2_000_000) return "Kim Cuong";
        if (amount >= 500_000) return "Vang";
        if (amount >= 100_000) return "Bac";
        return "Dong";
    }

    static String tierClass(String tier) {
        return switch (tier) {
            case "Kim Cuong" -> "tier-diamond";
            case "Vang" -> "tier-gold";
            case "Bac" -> "tier-silver";
            default -> "tier-bronze";
        };
    }

    static long totalRaised() {
        long sum = 0;
        for (Donation d : DONATIONS) if (isConfirmed(d)) sum += d.amount();
        return sum;
    }

    static List<Donation> confirmedDonations() {
        List<Donation> out = new ArrayList<>();
        for (Donation d : DONATIONS) if (isConfirmed(d)) out.add(d);
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    static void handleHome(HttpExchange ex) throws IOException {
        List<Donation> recent = confirmedDonations();
        StringBuilder wall = new StringBuilder();
        if (recent.isEmpty()) {
            wall.append("<p class=\"wall-empty\">Chua co ai ung ho ca — hay la nguoi dau tien!</p>");
        } else {
            for (Donation d : recent) {
                if (wall.length() > 4000) break; // gioi han hien thi
                wall.append("<div class=\"dot ").append(tierClass(d.tier())).append("\" title=\"")
                    .append(escape(d.name())).append(" — ").append(formatVND(d.amount())).append("\"></div>");
            }
        }

        String body = """
            <section class="hero">
              <div class="hero-inner">
                <p class="eyebrow">CONG DONG UNG HO OWNSTYLE</p>
                <h1>Chao mung den voi <span class="accent">ownstyle</span></h1>
                <p class="lead">Moi dong gop, du lon hay nho, deu giup ownstyle tiep tuc sang tao,
                   giu vung phong cach rieng va di duong dai hon.</p>
                <div class="hero-actions">
                  <a class="btn btn-primary" href="/donate">Ung ho ngay</a>
                  <a class="btn btn-ghost" href="/supporters">Xem nguoi ung ho</a>
                </div>
                <div class="hero-stats">
                  <div><span class="stat-num">%s</span><span class="stat-label">da quyen gop</span></div>
                  <div><span class="stat-num">%d</span><span class="stat-label">luot ung ho</span></div>
                </div>
              </div>
              <div class="hero-glow" aria-hidden="true"></div>
            </section>

            <section class="section">
              <p class="section-eyebrow">MUC UNG HO</p>
              <h2>Chon mot khoi dau phu hop</h2>
              <div class="tier-grid">
                <div class="tier-card tier-bronze">
                  <span class="tier-name">Dong</span>
                  <span class="tier-amount">20.000₫ +</span>
                  <p>Mot loi cam on nho, gop phan giu lua cho du an.</p>
                </div>
                <div class="tier-card tier-silver">
                  <span class="tier-name">Bac</span>
                  <span class="tier-amount">100.000₫ +</span>
                  <p>Ten cua ban xuat hien tren buc tuong nguoi ung ho.</p>
                </div>
                <div class="tier-card tier-gold">
                  <span class="tier-name">Vang</span>
                  <span class="tier-amount">500.000₫ +</span>
                  <p>Duoc uu tien nhac ten trong loi cam on hang thang.</p>
                </div>
                <div class="tier-card tier-diamond">
                  <span class="tier-name">Kim Cuong</span>
                  <span class="tier-amount">2.000.000₫ +</span>
                  <p>Nguoi dong hanh dac biet — cam on ban rat nhieu!</p>
                </div>
              </div>
              <div class="tier-cta"><a class="btn btn-primary" href="/donate">Ung ho ngay</a></div>
            </section>

            <section class="section section-alt">
              <p class="section-eyebrow">BUC TUONG UNG HO</p>
              <h2>Nhung nguoi da dong hanh gan day</h2>
              <div class="wall">%s</div>
              <div class="wall-legend">
                <span><i class="dot tier-bronze"></i> Dong</span>
                <span><i class="dot tier-silver"></i> Bac</span>
                <span><i class="dot tier-gold"></i> Vang</span>
                <span><i class="dot tier-diamond"></i> Kim Cuong</span>
              </div>
            </section>
            """.formatted(formatVND(totalRaised()), recent.size(), wall.toString());

        sendHtml(ex, 200, renderPage("OWNSTYLE — Trang chu", body, "home"));
    }

    static void handleDonatePage(HttpExchange ex) throws IOException {
        String error = null;
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        if ("1".equals(q.get("error"))) {
            error = "Vui long nhap so tien hop le va lon hon 0.";
        }

        String errorHtml = error == null ? "" :
            "<div class=\"form-error\">" + escape(error) + "</div>";

        String body = """
            <section class="section narrow">
              <p class="section-eyebrow">UNG HO</p>
              <h2>Gui mot mon qua nho cho ownstyle</h2>
              <p class="lead-sm">Chuyen khoan truc tiep vao tai khoan ca nhan cua ownstyle — khong qua trung gian.</p>
              %s
              <form class="donate-form" method="POST" action="/submit-donation">
                <div class="field-group">
                  <label class="field-label">Chon muc ung ho</label>
                  <div class="amount-grid">
                    <label class="amount-option">
                      <input type="radio" name="amount" value="20000" checked>
                      <span class="amount-card tier-bronze"><b>20.000₫</b><small>Dong</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="100000">
                      <span class="amount-card tier-silver"><b>100.000₫</b><small>Bac</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="500000">
                      <span class="amount-card tier-gold"><b>500.000₫</b><small>Vang</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="2000000">
                      <span class="amount-card tier-diamond"><b>2.000.000₫</b><small>Kim Cuong</small></span>
                    </label>
                    <label class="amount-option amount-option-custom">
                      <input type="radio" name="amount" value="custom" id="amt-custom">
                      <span class="amount-card"><b>Tuy chon</b><small>Nhap so tien</small></span>
                    </label>
                  </div>
                  <input class="custom-input" type="number" name="customAmount" min="1000" step="1000"
                         placeholder="Nhap so tien (VND), vi du 150000">
                </div>

                <div class="field-group">
                  <label class="field-label" for="name">Ten hien thi (khong bat buoc)</label>
                  <input class="text-input" id="name" type="text" name="name" maxlength="60" placeholder="An danh">
                </div>

                <div class="field-group">
                  <label class="field-label" for="message">Loi nhan (khong bat buoc)</label>
                  <textarea class="text-input" id="message" name="message" maxlength="200" rows="3"
                            placeholder="Gui loi chuc hoac dong vien cho ownstyle..."></textarea>
                </div>

                <button class="btn btn-primary btn-block" type="submit">Tiep tuc — lay ma QR</button>
              </form>
            </section>
            """.formatted(errorHtml);

        sendHtml(ex, 200, renderPage("OWNSTYLE — Ung ho", body, "donate"));
    }

    static void handleSubmitDonation(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendRedirect(ex, "/donate");
            return;
        }
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));

        String amountParam = form.getOrDefault("amount", "");
        String customAmount = form.getOrDefault("customAmount", "");
        long amount;
        try {
            if ("custom".equals(amountParam)) {
                amount = Long.parseLong(customAmount.trim());
            } else {
                amount = Long.parseLong(amountParam.trim());
            }
            if (amount <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            sendRedirect(ex, "/donate?error=1");
            return;
        }

        String name = form.getOrDefault("name", "").trim();
        if (name.isEmpty()) name = "An danh";
        String message = form.getOrDefault("message", "").trim();

        // Method chua duoc chon o buoc nay — nguoi ung ho se chon ngay tren trang /pay
        Donation d = new Donation(nextId(), name, message, amount, tierFor(amount), "", ZonedDateTime.now());
        DONATIONS.add(0, d);

        sendRedirect(ex, "/pay?id=" + d.id());
    }

    static Optional<Donation> findDonation(String id) {
        if (id == null) return Optional.empty();
        for (Donation d : DONATIONS) if (d.id().equals(id)) return Optional.of(d);
        return Optional.empty();
    }

    static void handlePayPage(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Optional<Donation> found = findDonation(q.get("id"));
        if (found.isEmpty()) { sendRedirect(ex, "/donate"); return; }
        Donation d = found.get();
        if (isConfirmed(d)) { sendRedirect(ex, "/thanks?id=" + d.id()); return; }

        String transferNote = "UNGHO " + d.id();
        String vietQrImg = vietQrUrl(d.amount(), transferNote);
        boolean zaloQrReady = new File(ZALOPAY_QR_FILE).exists();
        String zaloBlock = zaloQrReady
            ? "<img class=\"qr-img\" src=\"/assets/zalopay-qr.png\" alt=\"QR ZaloPay\">"
            : "<div class=\"qr-missing\">Chua co anh QR ZaloPay.<br>Xem huong dan.</div>";

        String body = """
            <section class="section narrow center">
              <p class="section-eyebrow">QUET MA DE CHUYEN KHOAN</p>
              <h2>%s — %s</h2>
              <p class="lead-sm">Noi dung chuyen khoan: <b>%s</b> (giup ownstyle doi soat nhanh hon)</p>

              <div class="pay-grid">
                <div class="pay-card">
                  <h3>Ngan hang (VietQR)</h3>
                  <img class="qr-img" src="%s" alt="QR ngan hang">
                  <p class="pay-meta"><b>%s</b><br>%s</p>
                </div>
                <div class="pay-card">
                  <h3>ZaloPay</h3>
                  %s
                  <p class="pay-meta"><b>%s</b><br>Vi ZaloPay ca nhan</p>
                </div>
              </div>

              <form method="POST" action="/confirm-payment">
                <input type="hidden" name="id" value="%s">
                <input type="hidden" name="method" value="Ngan hang / ZaloPay">
                <button class="btn btn-primary btn-block" type="submit">Toi da chuyen khoan xong</button>
              </form>
              <p class="fine-print">Khong co xac nhan tu dong — ownstyle se tu doi chieu sao ke va xac nhan.
                 Bam nut tren de ghi nhan luot ung ho cua ban vao danh sach.</p>
            </section>
            """.formatted(
                escape(d.name()), formatVND(d.amount()),
                escape(transferNote),
                vietQrImg, escape(BANK_ACCOUNT_NAME), escape(BANK_ACCOUNT_NO),
                zaloBlock, escape(ZALOPAY_PHONE),
                d.id()
            );

        sendHtml(ex, 200, renderPage("OWNSTYLE — Quet ma", body, "donate"));
    }

    static void handleConfirmPayment(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendRedirect(ex, "/donate"); return; }
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));
        String id = form.get("id");
        Optional<Donation> found = findDonation(id);
        if (found.isEmpty()) { sendRedirect(ex, "/donate"); return; }
        Donation old = found.get();
        Donation confirmed = new Donation(old.id(), old.name(), old.message(), old.amount(),
                old.tier(), "Chuyen khoan / ZaloPay (QR)", old.time());
        int idx = DONATIONS.indexOf(old);
        if (idx >= 0) DONATIONS.set(idx, confirmed);
        CONFIRMED_IDS.add(confirmed.id());
        sendRedirect(ex, "/thanks?id=" + id);
    }

    static void handleZaloQrImage(HttpExchange ex) throws IOException {
        File f = new File(ZALOPAY_QR_FILE);
        if (!f.exists()) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(f.toPath());
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String vietQrUrl(long amount, String addInfo) {
        String info = URLEncoder.encode(addInfo, StandardCharsets.UTF_8);
        String name = URLEncoder.encode(BANK_ACCOUNT_NAME, StandardCharsets.UTF_8);
        return "https://img.vietqr.io/image/" + BANK_BIN + "-" + BANK_ACCOUNT_NO
                + "-compact2.png?amount=" + amount + "&addInfo=" + info + "&accountName=" + name;
    }

    static void handleThanks(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        Donation found = null;
        for (Donation d : DONATIONS) if (d.id().equals(id)) { found = d; break; }

        String body;
        if (found == null) {
            body = """
                <section class="section narrow center">
                  <h2>Khong tim thay giao dich</h2>
                  <p class="lead-sm">Co the lien ket da het han. Quay lai trang ung ho de thu lai.</p>
                  <a class="btn btn-primary" href="/donate">Quay lai trang ung ho</a>
                </section>
                """;
        } else {
            body = """
                <section class="section narrow center">
                  <div class="thanks-badge %s">✓</div>
                  <p class="section-eyebrow">CAM ON BAN</p>
                  <h2>%s da ung ho %s</h2>
                  <p class="lead-sm">%s</p>
                  <div class="thanks-card">
                    <div class="thanks-row"><span>Ma giao dich</span><b>%s</b></div>
                    <div class="thanks-row"><span>Hang muc</span><b>%s</b></div>
                    <div class="thanks-row"><span>Phuong thuc</span><b>%s</b></div>
                  </div>
                  <p class="fine-print">Day la giao dich mo phong tren server Java (chua thu tien that).
                     De nhan thanh toan thuc te, can tich hop cong thanh toan da duoc cap phep
                     (VNPay, MoMo Business, PayOS, Stripe...).</p>
                  <div class="hero-actions">
                    <a class="btn btn-primary" href="/donate">Ung ho them</a>
                    <a class="btn btn-ghost" href="/supporters">Xem buc tuong ung ho</a>
                  </div>
                </section>
                """.formatted(
                    tierClass(found.tier()),
                    escape(found.name()), formatVND(found.amount()),
                    found.message().isEmpty() ? "Cam on ban da dong hanh cung ownstyle!" : "\u201C" + escape(found.message()) + "\u201D",
                    found.id(), found.tier(), escape(found.method())
                );
        }

        sendHtml(ex, 200, renderPage("OWNSTYLE — Cam on", body, "thanks"));
    }

    static void handleSupporters(HttpExchange ex) throws IOException {
        List<Donation> all = confirmedDonations();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.systemDefault());

        StringBuilder rows = new StringBuilder();
        if (all.isEmpty()) {
            rows.append("<tr><td colspan=\"5\" class=\"wall-empty\">Chua co du lieu.</td></tr>");
        } else {
            for (Donation d : all) {
                rows.append("<tr>")
                    .append("<td><span class=\"badge ").append(tierClass(d.tier())).append("\">")
                    .append(d.tier()).append("</span></td>")
                    .append("<td>").append(escape(d.name())).append("</td>")
                    .append("<td class=\"num\">").append(formatVND(d.amount())).append("</td>")
                    .append("<td>").append(escape(d.method())).append("</td>")
                    .append("<td class=\"muted\">").append(fmt.format(d.time())).append("</td>")
                    .append("</tr>");
                if (!d.message().isEmpty()) {
                    rows.append("<tr class=\"msg-row\"><td></td><td colspan=\"4\">\u201C")
                        .append(escape(d.message())).append("\u201D</td></tr>");
                }
            }
        }

        String body = """
            <section class="section">
              <p class="section-eyebrow">NGUOI UNG HO</p>
              <h2>Cam on tat ca %d luot ung ho — tong %s</h2>
              <div class="table-wrap">
                <table class="supporters-table">
                  <thead><tr><th>Hang</th><th>Ten</th><th>So tien</th><th>Phuong thuc</th><th>Thoi gian</th></tr></thead>
                  <tbody>%s</tbody>
                </table>
              </div>
              <div class="tier-cta"><a class="btn btn-primary" href="/donate">Tro thanh nguoi ung ho tiep theo</a></div>
            </section>
            """.formatted(all.size(), formatVND(totalRaised()), rows.toString());

        sendHtml(ex, 200, renderPage("OWNSTYLE — Nguoi ung ho", body, "supporters"));
    }

    static void handleCss(HttpExchange ex) throws IOException {
        byte[] bytes = CSS.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/css; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------------------------------------------------------------------
    // Layout dung chung
    // ---------------------------------------------------------------------

    static String renderPage(String title, String content, String active) {
        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <link rel="stylesheet" href="/assets/style.css">
            </head>
            <body>
              <header class="topbar">
                <a class="brand" href="/">OWNSTYLE</a>
                <nav>
                  <a href="/" class="%s">Trang chu</a>
                  <a href="/donate" class="%s">Ung ho</a>
                  <a href="/supporters" class="%s">Nguoi ung ho</a>
                </nav>
              </header>
              <main>%s</main>
              <footer class="footer">
                <p>OWNSTYLE — website ung ho viet hoan toan bang Java (com.sun.net.httpserver), khong dung framework ngoai.</p>
              </footer>
            </body>
            </html>
            """.formatted(
                escape(title),
                active.equals("home") ? "active" : "",
                active.equals("donate") ? "active" : "",
                active.equals("supporters") ? "active" : "",
                content
            );
    }

    // ---------------------------------------------------------------------
    // Tien ich
    // ---------------------------------------------------------------------

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendRedirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    static Map<String, String> parseForm(String body) {
        return parseQuery(body);
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String formatVND(long amount) {
        String s = Long.toString(amount);
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            cnt++;
            if (cnt % 3 == 0 && i != 0) sb.append('.');
        }
        return sb.reverse().toString() + "₫";
    }

    // ---------------------------------------------------------------------
    // CSS (nhung tren duoc sinh boi Java, khong can file rieng)
    // ---------------------------------------------------------------------

    static final String CSS = """
        @font-face { font-family: 'system-serif'; src: local('Georgia'); }
        :root {
          --bg: #0B0D12;
          --bg-alt: #10131b;
          --card: #151925;
          --border: #262b3a;
          --text: #F3EFE6;
          --muted: #9AA3B5;
          --gold: #D8B45C;
          --teal: #3FBFA0;
          --bronze: #b0876a;
          --silver: #b9c2d0;
          --diamond: #7fd8e8;
          --radius: 14px;
        }
        * { box-sizing: border-box; }
        html { scroll-behavior: smooth; }
        body {
          margin: 0;
          background: radial-gradient(1200px 600px at 15% -10%, #1a1f2e 0%, var(--bg) 55%) fixed;
          color: var(--text);
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
          line-height: 1.55;
        }
        a { color: inherit; text-decoration: none; }
        h1, h2 { font-family: Georgia, "Times New Roman", serif; letter-spacing: 0.2px; margin: 0.2em 0 0.4em; }
        h1 { font-size: clamp(2.1rem, 5vw, 3.4rem); }
        h2 { font-size: clamp(1.5rem, 3vw, 2.1rem); }

        .topbar {
          display: flex; align-items: center; justify-content: space-between;
          padding: 22px 6vw; position: sticky; top: 0; z-index: 20;
          background: rgba(11,13,18,0.78); backdrop-filter: blur(10px);
          border-bottom: 1px solid var(--border);
        }
        .brand { font-family: Georgia, serif; font-weight: 700; letter-spacing: 3px; font-size: 1.05rem; color: var(--gold); }
        .topbar nav { display: flex; gap: 28px; }
        .topbar nav a { color: var(--muted); font-size: 0.92rem; padding-bottom: 4px; border-bottom: 2px solid transparent; transition: color .2s, border-color .2s; }
        .topbar nav a:hover, .topbar nav a.active { color: var(--text); border-color: var(--gold); }

        main { max-width: 1080px; margin: 0 auto; padding: 0 6vw; }

        .hero { position: relative; padding: 90px 0 70px; overflow: hidden; }
        .hero-inner { position: relative; z-index: 2; max-width: 640px; }
        .eyebrow, .section-eyebrow { color: var(--gold); letter-spacing: 3px; font-size: 0.78rem; font-weight: 600; text-transform: uppercase; margin: 0 0 10px; }
        .lead { color: var(--muted); font-size: 1.08rem; max-width: 520px; }
        .lead-sm { color: var(--muted); font-size: 0.98rem; }
        .accent { color: var(--gold); }
        .hero-glow {
          position: absolute; right: -120px; top: -80px; width: 480px; height: 480px; border-radius: 50%;
          background: radial-gradient(circle, rgba(216,180,92,0.28), rgba(63,191,160,0.10) 55%, transparent 72%);
          filter: blur(10px); z-index: 1;
        }
        .hero-actions { display: flex; gap: 14px; margin: 28px 0 40px; flex-wrap: wrap; }
        .btn { display: inline-block; padding: 13px 26px; border-radius: 999px; font-size: 0.95rem; font-weight: 600; transition: transform .15s, box-shadow .15s, background .15s; border: 1px solid transparent; }
        .btn-primary { background: linear-gradient(135deg, var(--gold), #c99a3e); color: #1a1406; box-shadow: 0 8px 24px -8px rgba(216,180,92,0.55); }
        .btn-primary:hover { transform: translateY(-2px); box-shadow: 0 12px 28px -8px rgba(216,180,92,0.65); }
        .btn-ghost { border-color: var(--border); color: var(--text); }
        .btn-ghost:hover { border-color: var(--gold); color: var(--gold); }
        .btn-block { display: block; width: 100%; text-align: center; padding: 15px; margin-top: 8px; }
        .hero-stats { display: flex; gap: 40px; }
        .hero-stats > div { display: flex; flex-direction: column; }
        .stat-num { font-family: Georgia, serif; font-size: 1.7rem; color: var(--text); }
        .stat-label { color: var(--muted); font-size: 0.82rem; }

        .section { padding: 64px 0; border-top: 1px solid var(--border); }
        .section.narrow { max-width: 620px; margin: 0 auto; }
        .section.center { text-align: center; }
        .section-alt { }

        .tier-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin-top: 26px; }
        .tier-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 24px; border-top: 3px solid var(--muted); transition: transform .15s, border-color .15s; }
        .tier-card:hover { transform: translateY(-4px); }
        .tier-card p { color: var(--muted); font-size: 0.88rem; margin: 10px 0 0; }
        .tier-name { display: block; font-family: Georgia, serif; font-size: 1.1rem; margin-bottom: 4px; }
        .tier-amount { display: block; color: var(--gold); font-weight: 700; font-size: 1.15rem; }
        .tier-bronze { border-top-color: var(--bronze); }
        .tier-silver { border-top-color: var(--silver); }
        .tier-gold { border-top-color: var(--gold); }
        .tier-diamond { border-top-color: var(--diamond); }
        .tier-cta { margin-top: 30px; }

        .wall { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 24px; max-width: 720px; }
        .dot { width: 16px; height: 16px; border-radius: 50%; background: var(--muted); flex: 0 0 auto; }
        .dot.tier-bronze { background: var(--bronze); box-shadow: 0 0 10px -2px var(--bronze); }
        .dot.tier-silver { background: var(--silver); box-shadow: 0 0 10px -2px var(--silver); }
        .dot.tier-gold { background: var(--gold); box-shadow: 0 0 10px -2px var(--gold); }
        .dot.tier-diamond { background: var(--diamond); box-shadow: 0 0 10px -2px var(--diamond); }
        .wall-legend { display: flex; gap: 22px; margin-top: 20px; color: var(--muted); font-size: 0.82rem; flex-wrap: wrap; }
        .wall-legend .dot { width: 10px; height: 10px; margin-right: 6px; vertical-align: middle; display: inline-block; }
        .wall-empty { color: var(--muted); }

        .pay-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin: 28px 0; text-align: left; }
        .pay-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 22px; text-align: center; }
        .pay-card h3 { font-family: Georgia, serif; font-size: 1.05rem; margin: 0 0 14px; color: var(--gold); }
        .qr-img { width: 100%; max-width: 220px; border-radius: 10px; background: #fff; padding: 8px; margin: 0 auto; display: block; }
        .qr-missing { width: 100%; max-width: 220px; aspect-ratio: 1; margin: 0 auto; display: flex; align-items: center; justify-content: center; text-align: center; background: var(--bg-alt); border: 1px dashed var(--border); border-radius: 10px; color: var(--muted); font-size: 0.82rem; padding: 12px; }
        .pay-meta { margin-top: 14px; color: var(--muted); font-size: 0.85rem; }
        .pay-meta b { color: var(--text); font-family: Georgia, serif; font-size: 1rem; }

        .form-error { background: rgba(216,92,92,0.12); border: 1px solid rgba(216,92,92,0.4); color: #f2a3a3; padding: 12px 16px; border-radius: 10px; margin: 16px 0; font-size: 0.9rem; }

        .donate-form { margin-top: 24px; }
        .field-group { margin-bottom: 26px; }
        .field-label { display: block; font-size: 0.85rem; color: var(--muted); margin-bottom: 10px; }
        .amount-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; }
        .amount-option input { position: absolute; opacity: 0; pointer-events: none; }
        .amount-card { display: flex; flex-direction: column; gap: 3px; border: 1px solid var(--border); background: var(--card); border-radius: 10px; padding: 14px; cursor: pointer; border-top: 3px solid var(--muted); transition: border-color .15s, background .15s; }
        .amount-card small { color: var(--muted); font-size: 0.75rem; }
        .amount-option input:checked + .amount-card { border-color: var(--gold); background: #1c1810; }
        .amount-option-custom .amount-card { border-top-color: var(--teal); }
        .custom-input, .text-input {
          width: 100%; margin-top: 12px; background: var(--bg-alt); border: 1px solid var(--border); color: var(--text);
          border-radius: 10px; padding: 13px 14px; font-size: 0.95rem; font-family: inherit;
        }
        .custom-input:focus, .text-input:focus { outline: none; border-color: var(--gold); }
        textarea.text-input { resize: vertical; }
        .method-grid { display: flex; gap: 10px; flex-wrap: wrap; }
        .method-option input { position: absolute; opacity: 0; pointer-events: none; }
        .method-option span { display: inline-block; border: 1px solid var(--border); background: var(--card); padding: 10px 16px; border-radius: 999px; font-size: 0.88rem; cursor: pointer; }
        .method-option input:checked + span { border-color: var(--teal); color: var(--teal); }

        .thanks-badge { width: 64px; height: 64px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 1.6rem; margin: 0 auto 18px; background: var(--card); border: 2px solid var(--gold); color: var(--gold); }
        .thanks-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 20px 24px; margin: 24px auto; max-width: 380px; text-align: left; }
        .thanks-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 0.9rem; }
        .thanks-row:last-child { border-bottom: none; }
        .thanks-row span { color: var(--muted); }
        .fine-print { color: var(--muted); font-size: 0.8rem; max-width: 460px; margin: 10px auto 0; }

        .table-wrap { overflow-x: auto; margin-top: 24px; border: 1px solid var(--border); border-radius: var(--radius); }
        .supporters-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
        .supporters-table th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.78rem; text-transform: uppercase; letter-spacing: 1px; padding: 14px 16px; border-bottom: 1px solid var(--border); }
        .supporters-table td { padding: 12px 16px; border-bottom: 1px solid var(--border); }
        .supporters-table tr:last-child td { border-bottom: none; }
        .supporters-table td.num { color: var(--gold); font-weight: 600; }
        .supporters-table td.muted { color: var(--muted); font-size: 0.82rem; }
        .msg-row td { color: var(--muted); font-style: italic; font-size: 0.85rem; padding-top: 0; }
        .badge { padding: 4px 10px; border-radius: 999px; font-size: 0.74rem; font-weight: 700; border: 1px solid var(--border); }
        .badge.tier-bronze { color: var(--bronze); border-color: var(--bronze); }
        .badge.tier-silver { color: var(--silver); border-color: var(--silver); }
        .badge.tier-gold { color: var(--gold); border-color: var(--gold); }
        .badge.tier-diamond { color: var(--diamond); border-color: var(--diamond); }

        .footer { border-top: 1px solid var(--border); padding: 30px 6vw 50px; color: var(--muted); font-size: 0.78rem; text-align: center; }

        @media (max-width: 640px) {
          .topbar { padding: 18px 5vw; }
          .topbar nav { gap: 16px; }
          .hero { padding: 60px 0 40px; }
        }
        """;
}