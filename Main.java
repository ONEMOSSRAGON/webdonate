import com.sun.net.httpserver.HttpExchange;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OWNSTYLE — website ca nhan viet hoan toan bang Java (mot file duy nhat,
 * chi dung thu vien co san trong JDK: com.sun.net.httpserver).
 * Khong can framework, khong can javac rieng — chay bang: java Main.java
 *
 * Cac muc: Trang chu, Gioi thieu, Tin tuc, Vat pham (shop), Ung ho.
 * He thong thanh toan: QR VietQR dong (qua SePay) + webhook SePay de
 * tu dong xac nhan giao dich that — dung chung cho ca Ung ho lan Mua hang.
 */
public class Main {

    // =====================================================================
    // CAU HINH CHUNG — SUA LAI THANH THONG TIN THAT CUA BAN
    // =====================================================================
    static final String SITE_NAME = "OWNSTYLE";
    static final String SITE_TAGLINE = "Phong cach rieng — cau chuyen rieng";

    // --- Tai khoan nhan tien (VietQR) ---
    // Ma ngan hang pho bien: Vietcombank=970436 BIDV=970418 MB=970422
    // Techcombank=970407 ACB=970416 TPBank=970422... TPBank thuc te la 970423
    // (kiem tra chinh xac tai vietqr.app/banks.json neu can)
    static final String BANK_BIN = "970423";
    static final String BANK_ACCOUNT_NO = "SO_TAI_KHOAN_CUA_BAN";
    static final String BANK_ACCOUNT_NAME = "NGUYEN VAN A";

    // --- ZaloPay ca nhan (du phong, xac nhan thu cong) ---
    static final String ZALOPAY_PHONE = "09xxxxxxxx";
    static final String ZALOPAY_QR_FILE = "zalopay-qr.png";

    // --- SePay Webhook (de tu dong xac nhan giao dich ngan hang that) ---
    // Tao tai my.sepay.vn/webhooks, chon xac thuc "API Key", dan key vao day.
    static final String SEPAY_API_KEY = "DAT_API_KEY_WEBHOOK_CUA_BAN_O_DAY";

    static final int PORT = resolvePort();
    static int resolvePort() {
        String env = System.getenv("PORT");
        if (env != null) { try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {} }
        return 8080;
    }

    // =====================================================================
    // DU LIEU (trong bo nho — mat khi restart server)
    // =====================================================================

    record Order(String id, String kind, String itemName, String buyerName, String message,
                 long amount, String tier, String method, ZonedDateTime time) {}

    record Product(String id, String name, long price, String category, String description, String accent) {}

    record NewsPost(String id, String title, String date, String tag, String summary) {}

    static final List<Order> ORDERS = new CopyOnWriteArrayList<>();
    static final Set<String> CONFIRMED_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final Set<Long> PROCESSED_WEBHOOK_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final AtomicInteger ID_SEQ = new AtomicInteger(1000);

    // ---- Vat pham dang ban — sua/them/xoa truc tiep trong danh sach nay ----
    static final List<Product> PRODUCTS = List.of(
        new Product("P1", "Ao thun OWNSTYLE Basic", 250_000, "Ao", "Chat lieu cotton 100%, form regular, in logo toi gian.", "tier-gold"),
        new Product("P2", "Tui tote canvas", 150_000, "Phu kien", "Tui vai canvas day dan, in hoa tiet doc quyen OWNSTYLE.", "tier-silver"),
        new Product("P3", "Pin cai ao OWNSTYLE", 40_000, "Phu kien", "Set 3 pin kim loai, thiet ke lay cam hung tu bo nhan dien.", "tier-bronze"),
        new Product("P4", "Hoodie OWNSTYLE Limited", 550_000, "Ao", "Ban gioi han, ni bong day dan, thieu logo noi 3D.", "tier-diamond")
    );

    // ---- Tin tuc — sua/them/xoa truc tiep trong danh sach nay ----
    static final List<NewsPost> NEWS = List.of(
        new NewsPost("N1", "OWNSTYLE chinh thuc ra mat website rieng", "2026-07-20", "Thong bao",
            "Sau thoi gian ap u, OWNSTYLE chinh thuc co website rieng — noi tong hop moi thu ve thuong hieu: cau chuyen, san pham va cach de moi nguoi dong hanh cung minh."),
        new NewsPost("N2", "Bo suu tap Hoodie Limited da co mat", "2026-07-15", "San pham",
            "Mau Hoodie gioi han so luong da len ke tai muc Vat pham. Moi nguoi quan tam co the dat truoc de khong bi het hang."),
        new NewsPost("N3", "Cam on nhung nguoi ung ho dau tien", "2026-07-01", "Cam on",
            "Nhung luot ung ho dau tien da giup OWNSTYLE co dong luc de tiep tuc. Cam on tat ca moi nguoi da tin tuong va dong hanh ngay tu nhung ngay dau.")
    );

    static void seedDemoOrders() {
        addConfirmed(new Order(nextId(), "donate", "", "Minh Anh", "Chuc trang ngay cang phat trien!",
                500_000, tierFor(500_000), "Chuyen khoan ngan hang", ZonedDateTime.now().minusHours(5)));
        addConfirmed(new Order(nextId(), "donate", "", "An danh", "Ung ho tinh than :)",
                50_000, tierFor(50_000), "ZaloPay", ZonedDateTime.now().minusHours(2)));
        addConfirmed(new Order(nextId(), "donate", "", "Gia Han", "Rat thich phong cach cua ban!",
                2_000_000, tierFor(2_000_000), "Chuyen khoan ngan hang", ZonedDateTime.now().minusMinutes(30)));
    }

    static void addConfirmed(Order o) { ORDERS.add(o); CONFIRMED_IDS.add(o.id()); }
    static boolean isConfirmed(Order o) { return CONFIRMED_IDS.contains(o.id()); }
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
        for (Order o : ORDERS) if (isConfirmed(o) && "donate".equals(o.kind())) sum += o.amount();
        return sum;
    }
    static List<Order> confirmedDonations() {
        List<Order> out = new ArrayList<>();
        for (Order o : ORDERS) if (isConfirmed(o) && "donate".equals(o.kind())) out.add(o);
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }
    static Optional<Order> findOrder(String id) {
        if (id == null) return Optional.empty();
        for (Order o : ORDERS) if (o.id().equals(id)) return Optional.of(o);
        return Optional.empty();
    }
    static Optional<Product> findProduct(String id) {
        for (Product p : PRODUCTS) if (p.id().equals(id)) return Optional.of(p);
        return Optional.empty();
    }

    // =====================================================================
    // MAIN
    // =====================================================================

    public static void main(String[] args) throws IOException {
        seedDemoOrders();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleHome);
        server.createContext("/gioi-thieu", Main::handleAbout);
        server.createContext("/tin-tuc", Main::handleNews);
        server.createContext("/vat-pham", Main::handleShop);
        server.createContext("/mua-hang", Main::handleBuyPage);
        server.createContext("/submit-order", Main::handleSubmitProductOrder);
        server.createContext("/ung-ho", Main::handleDonatePage);
        server.createContext("/submit-donation", Main::handleSubmitDonation);
        server.createContext("/pay", Main::handlePayPage);
        server.createContext("/confirm-payment", Main::handleConfirmPayment);
        server.createContext("/thanks", Main::handleThanks);
        server.createContext("/nguoi-ung-ho", Main::handleSupporters);
        server.createContext("/assets/style.css", Main::handleCss);
        server.createContext("/assets/zalopay-qr.png", Main::handleZaloQrImage);
        server.createContext("/sepay-webhook", Main::handleSepayWebhook);
        server.createContext("/favicon.ico", ex -> ex.sendResponseHeaders(204, -1));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("=======================================================");
        System.out.println(" " + SITE_NAME + " dang chay tai: http://localhost:" + PORT);
        System.out.println(" Nhan Ctrl+C de dung server.");
        System.out.println("=======================================================");
    }

    // =====================================================================
    // TRANG CHU
    // =====================================================================

    static void handleHome(HttpExchange ex) throws IOException {
        // Neu la duong dan con khong khop route nao (vd loi go /abc), van tra ve trang chu cho "/"
        if (!"/".equals(ex.getRequestURI().getPath())) { send404(ex); return; }

        List<Order> recent = confirmedDonations();
        StringBuilder wall = new StringBuilder();
        if (recent.isEmpty()) {
            wall.append("<p class=\"wall-empty\">Chua co ai ung ho ca — hay la nguoi dau tien!</p>");
        } else {
            int shown = 0;
            for (Order o : recent) {
                if (shown++ >= 60) break;
                wall.append("<div class=\"dot ").append(tierClass(o.tier())).append("\" title=\"")
                    .append(escape(o.buyerName())).append(" — ").append(formatVND(o.amount())).append("\"></div>");
            }
        }

        StringBuilder productCards = new StringBuilder();
        int shownP = 0;
        for (Product p : PRODUCTS) {
            if (shownP++ >= 3) break;
            productCards.append(productCardHtml(p));
        }

        StringBuilder newsTeaser = new StringBuilder();
        int shownN = 0;
        for (NewsPost n : NEWS) {
            if (shownN++ >= 2) break;
            newsTeaser.append(newsCardHtml(n, true));
        }

        String body = """
            <section class="hero">
              <div class="hero-glow" aria-hidden="true"></div>
              <div class="hero-inner">
                <p class="eyebrow">%s</p>
                <h1>Chao mung den voi <span class="accent">ownstyle</span></h1>
                <p class="lead">%s. Day la noi tong hop cau chuyen, san pham va cach de moi nguoi
                   dong hanh cung minh — tu nhung dieu nho nhat.</p>
                <div class="hero-actions">
                  <a class="btn btn-primary" href="/ung-ho">Ung ho ngay</a>
                  <a class="btn btn-ghost" href="/vat-pham">Xem vat pham</a>
                </div>
                <div class="hero-stats">
                  <div><span class="stat-num">%s</span><span class="stat-label">da quyen gop</span></div>
                  <div><span class="stat-num">%d</span><span class="stat-label">luot ung ho</span></div>
                  <div><span class="stat-num">%d</span><span class="stat-label">vat pham dang ban</span></div>
                </div>
              </div>
            </section>

            <section class="section">
              <div class="section-head">
                <div><p class="section-eyebrow">VAT PHAM NOI BAT</p><h2>Tu bo suu tap OWNSTYLE</h2></div>
                <a class="link-more" href="/vat-pham">Xem tat ca &rarr;</a>
              </div>
              <div class="product-grid">%s</div>
            </section>

            <section class="section section-alt">
              <div class="section-head">
                <div><p class="section-eyebrow">TIN TUC</p><h2>Cap nhat gan day</h2></div>
                <a class="link-more" href="/tin-tuc">Xem tat ca &rarr;</a>
              </div>
              <div class="news-grid">%s</div>
            </section>

            <section class="section">
              <p class="section-eyebrow">BUC TUONG UNG HO</p>
              <h2>Nhung nguoi da dong hanh gan day</h2>
              <div class="wall">%s</div>
              <div class="wall-legend">
                <span><i class="dot tier-bronze"></i> Dong</span>
                <span><i class="dot tier-silver"></i> Bac</span>
                <span><i class="dot tier-gold"></i> Vang</span>
                <span><i class="dot tier-diamond"></i> Kim Cuong</span>
              </div>
              <div class="tier-cta"><a class="btn btn-primary" href="/ung-ho">Ung ho ngay</a></div>
            </section>
            """.formatted(
                SITE_TAGLINE.toUpperCase(), SITE_TAGLINE,
                formatVND(totalRaised()), recent.size(), PRODUCTS.size(),
                productCards.toString(), newsTeaser.toString(), wall.toString()
            );

        sendHtml(ex, 200, renderPage(SITE_NAME + " — Trang chu", body, "home"));
    }

    // =====================================================================
    // GIOI THIEU
    // =====================================================================

    static void handleAbout(HttpExchange ex) throws IOException {
        String body = """
            <section class="section narrow">
              <p class="section-eyebrow">GIOI THIEU</p>
              <h2>Cau chuyen phia sau OWNSTYLE</h2>
              <p class="lead-sm">OWNSTYLE bat dau tu mot so thich ca nhan ve phong cach — roi dan tro thanh
                 mot khong gian nho de chia se nhung gi minh tao ra: tu bai viet, san pham, den nhung
                 y tuong con dang thu nghiem.</p>
              <p class="lead-sm">Website nay duoc xay dung va van hanh boi chinh ca nhan minh, khong phai
                 mot cong ty hay doi ngu lon. Moi luot ung ho hoac mua vat pham deu duoc tran trong va
                 giup minh co them dong luc, thoi gian de tiep tuc theo duoi con duong nay.</p>

              <div class="value-grid">
                <div class="value-card">
                  <span class="value-icon">01</span>
                  <h3>Chan thuc</h3>
                  <p>Moi noi dung, san pham deu xuat phat tu trai nghiem va gu tham my that.</p>
                </div>
                <div class="value-card">
                  <span class="value-icon">02</span>
                  <h3>Ben vung</h3>
                  <p>Uu tien chat luong hon so luong — lam it nhung lam dang hoang.</p>
                </div>
                <div class="value-card">
                  <span class="value-icon">03</span>
                  <h3>Minh bach</h3>
                  <p>Moi khoan ung ho deu chuyen thang, khong qua trung gian, khong an phi an.</p>
                </div>
              </div>

              <div class="tier-cta"><a class="btn btn-primary" href="/ung-ho">Dong hanh cung OWNSTYLE</a></div>
            </section>
            """;
        sendHtml(ex, 200, renderPage(SITE_NAME + " — Gioi thieu", body, "about"));
    }

    // =====================================================================
    // TIN TUC
    // =====================================================================

    static void handleNews(HttpExchange ex) throws IOException {
        StringBuilder cards = new StringBuilder();
        for (NewsPost n : NEWS) cards.append(newsCardHtml(n, false));

        String body = """
            <section class="section">
              <p class="section-eyebrow">TIN TUC</p>
              <h2>Moi cap nhat tu OWNSTYLE</h2>
              <div class="news-grid news-grid-full">%s</div>
            </section>
            """.formatted(cards.toString());
        sendHtml(ex, 200, renderPage(SITE_NAME + " — Tin tuc", body, "news"));
    }

    static String newsCardHtml(NewsPost n, boolean teaser) {
        return """
            <article class="news-card">
              <span class="news-tag">%s</span>
              <h3>%s</h3>
              <p class="news-date">%s</p>
              <p class="news-summary">%s</p>
            </article>
            """.formatted(escape(n.tag()), escape(n.title()), escape(n.date()), escape(n.summary()));
    }

    // =====================================================================
    // VAT PHAM (SHOP)
    // =====================================================================

    static void handleShop(HttpExchange ex) throws IOException {
        StringBuilder cards = new StringBuilder();
        for (Product p : PRODUCTS) cards.append(productCardHtml(p));

        String body = """
            <section class="section">
              <p class="section-eyebrow">VAT PHAM</p>
              <h2>Thu vien vat pham dang ban</h2>
              <p class="lead-sm">Thanh toan truc tiep qua chuyen khoan ngan hang (QR dong, tu dong xac nhan)
                 hoac ZaloPay — khong can tai khoan, khong can dang ky.</p>
              <div class="product-grid product-grid-full">%s</div>
            </section>
            """.formatted(cards.toString());
        sendHtml(ex, 200, renderPage(SITE_NAME + " — Vat pham", body, "shop"));
    }

    static String productCardHtml(Product p) {
        return """
            <div class="product-card %s">
              <div class="product-thumb">%s</div>
              <span class="product-cat">%s</span>
              <h3>%s</h3>
              <p class="product-desc">%s</p>
              <div class="product-foot">
                <span class="product-price">%s</span>
                <a class="btn btn-primary btn-sm" href="/mua-hang?id=%s">Mua ngay</a>
              </div>
            </div>
            """.formatted(p.accent(), initials(p.name()), escape(p.category()), escape(p.name()),
                escape(p.description()), formatVND(p.price()), p.id());
    }

    static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) { if (!s.isEmpty()) sb.append(Character.toUpperCase(s.charAt(0))); if (sb.length() >= 2) break; }
        return sb.length() == 0 ? "OS" : sb.toString();
    }

    static void handleBuyPage(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Optional<Product> found = findProduct(q.get("id"));
        if (found.isEmpty()) { sendRedirect(ex, "/vat-pham"); return; }
        Product p = found.get();

        String error = "1".equals(q.get("error")) ? "<div class=\"form-error\">Vui long dien day du thong tin.</div>" : "";

        String body = """
            <section class="section narrow">
              <p class="section-eyebrow">MUA VAT PHAM</p>
              <h2>%s</h2>
              <p class="lead-sm">%s</p>
              <p class="lead-sm"><b>Don gia: %s</b></p>
              %s
              <form class="donate-form" method="POST" action="/submit-order">
                <input type="hidden" name="productId" value="%s">
                <div class="field-group">
                  <label class="field-label">So luong</label>
                  <div class="method-grid">
                    <label class="method-option"><input type="radio" name="qty" value="1" checked><span>1</span></label>
                    <label class="method-option"><input type="radio" name="qty" value="2"><span>2</span></label>
                    <label class="method-option"><input type="radio" name="qty" value="3"><span>3</span></label>
                  </div>
                </div>
                <div class="field-group">
                  <label class="field-label" for="name">Ten nguoi nhan</label>
                  <input class="text-input" id="name" type="text" name="name" maxlength="60" required placeholder="Nguyen Van A">
                </div>
                <div class="field-group">
                  <label class="field-label" for="message">Dia chi / ghi chu giao hang (khong bat buoc)</label>
                  <textarea class="text-input" id="message" name="message" maxlength="200" rows="3"
                            placeholder="Dia chi nhan hang, so dien thoai, size ao..."></textarea>
                </div>
                <button class="btn btn-primary btn-block" type="submit">Tiep tuc — lay ma QR thanh toan</button>
              </form>
            </section>
            """.formatted(escape(p.name()), escape(p.description()), formatVND(p.price()), error, p.id());

        sendHtml(ex, 200, renderPage(SITE_NAME + " — " + p.name(), body, "shop"));
    }

    static void handleSubmitProductOrder(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendRedirect(ex, "/vat-pham"); return; }
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));

        Optional<Product> found = findProduct(form.get("productId"));
        String name = form.getOrDefault("name", "").trim();
        if (found.isEmpty() || name.isEmpty()) {
            sendRedirect(ex, "/mua-hang?id=" + form.getOrDefault("productId", "") + "&error=1");
            return;
        }
        int qty;
        try { qty = Math.max(1, Math.min(10, Integer.parseInt(form.getOrDefault("qty", "1").trim()))); }
        catch (Exception e) { qty = 1; }

        Product p = found.get();
        long amount = p.price() * qty;
        String message = form.getOrDefault("message", "").trim();
        String itemName = p.name() + (qty > 1 ? " x" + qty : "");

        Order o = new Order(nextId(), "product", itemName, name, message, amount, "", "", ZonedDateTime.now());
        ORDERS.add(0, o);
        sendRedirect(ex, "/pay?id=" + o.id());
    }

    // =====================================================================
    // UNG HO
    // =====================================================================

    static void handleDonatePage(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String error = "1".equals(q.get("error"))
            ? "<div class=\"form-error\">Vui long nhap so tien hop le va lon hon 0.</div>" : "";

        String body = """
            <section class="section narrow">
              <p class="section-eyebrow">UNG HO</p>
              <h2>Gui mot mon qua nho cho ownstyle</h2>
              <p class="lead-sm">Chuyen khoan truc tiep vao tai khoan ca nhan — khong qua trung gian.</p>
              %s
              <form class="donate-form" method="POST" action="/submit-donation">
                <div class="field-group">
                  <label class="field-label">Chon muc ung ho</label>
                  <div class="amount-grid">
                    <label class="amount-option">
                      <input type="radio" name="amount" value="20000" checked>
                      <span class="amount-card tier-bronze"><b>20.000&#8363;</b><small>Dong</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="100000">
                      <span class="amount-card tier-silver"><b>100.000&#8363;</b><small>Bac</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="500000">
                      <span class="amount-card tier-gold"><b>500.000&#8363;</b><small>Vang</small></span>
                    </label>
                    <label class="amount-option">
                      <input type="radio" name="amount" value="2000000">
                      <span class="amount-card tier-diamond"><b>2.000.000&#8363;</b><small>Kim Cuong</small></span>
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
            """.formatted(error);

        sendHtml(ex, 200, renderPage(SITE_NAME + " — Ung ho", body, "donate"));
    }

    static void handleSubmitDonation(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendRedirect(ex, "/ung-ho"); return; }
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));

        String amountParam = form.getOrDefault("amount", "");
        String customAmount = form.getOrDefault("customAmount", "");
        long amount;
        try {
            amount = "custom".equals(amountParam) ? Long.parseLong(customAmount.trim()) : Long.parseLong(amountParam.trim());
            if (amount <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            sendRedirect(ex, "/ung-ho?error=1");
            return;
        }

        String name = form.getOrDefault("name", "").trim();
        if (name.isEmpty()) name = "An danh";
        String message = form.getOrDefault("message", "").trim();

        Order o = new Order(nextId(), "donate", "", name, message, amount, tierFor(amount), "", ZonedDateTime.now());
        ORDERS.add(0, o);
        sendRedirect(ex, "/pay?id=" + o.id());
    }

    // =====================================================================
    // THANH TOAN (dung chung cho ung ho + mua vat pham)
    // =====================================================================

    static void handlePayPage(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Optional<Order> found = findOrder(q.get("id"));
        if (found.isEmpty()) { sendRedirect(ex, "/"); return; }
        Order o = found.get();
        if (isConfirmed(o)) { sendRedirect(ex, "/thanks?id=" + o.id()); return; }

        boolean isProduct = "product".equals(o.kind());
        String heading = isProduct ? o.itemName() : (o.buyerName() + " — " + formatVND(o.amount()));
        String transferNote = "OWNSTYLE " + o.id();
        String vietQrImg = vietQrUrl(o.amount(), transferNote);
        boolean zaloQrReady = new File(ZALOPAY_QR_FILE).exists();
        String zaloBlock = zaloQrReady
            ? "<img class=\"qr-img\" src=\"/assets/zalopay-qr.png\" alt=\"QR ZaloPay\">"
            : "<div class=\"qr-missing\">Chua co anh QR ZaloPay.<br>Xem huong dan.</div>";

        String body = """
            <section class="section narrow center">
              <p class="section-eyebrow">QUET MA DE CHUYEN KHOAN</p>
              <h2>%s</h2>
              <p class="lead-sm">So tien: <b>%s</b> — Noi dung chuyen khoan: <b>%s</b></p>

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
                <button class="btn btn-primary btn-block" type="submit">Toi da chuyen khoan xong</button>
              </form>
              <p class="fine-print">Chuyen khoan ngan hang (VietQR) se duoc SePay tu dong xac nhan trong
                 vai giay — trang nay se tu lam moi va chuyen sang trang cam on. Neu chuyen qua ZaloPay
                 hoac muon xac nhan ngay, hay bam nut ben tren.</p>
            </section>
            """.formatted(
                escape(heading), formatVND(o.amount()), escape(transferNote),
                vietQrImg, escape(BANK_ACCOUNT_NAME), escape(BANK_ACCOUNT_NO),
                zaloBlock, escape(ZALOPAY_PHONE),
                o.id()
            );

        sendHtml(ex, 200, renderPage(SITE_NAME + " — Quet ma", body, isProduct ? "shop" : "donate",
                "<meta http-equiv=\"refresh\" content=\"5\">"));
    }

    static void handleConfirmPayment(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendRedirect(ex, "/"); return; }
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(raw, StandardCharsets.UTF_8));
        String id = form.get("id");
        Optional<Order> found = findOrder(id);
        if (found.isEmpty()) { sendRedirect(ex, "/"); return; }
        confirmOrder(found.get(), "Chuyen khoan / ZaloPay (QR)");
        sendRedirect(ex, "/thanks?id=" + id);
    }

    static void confirmOrder(Order old, String methodLabel) {
        if (isConfirmed(old)) return;
        Order confirmed = new Order(old.id(), old.kind(), old.itemName(), old.buyerName(), old.message(),
                old.amount(), old.tier(), methodLabel, old.time());
        int idx = ORDERS.indexOf(old);
        if (idx >= 0) ORDERS.set(idx, confirmed);
        CONFIRMED_IDS.add(confirmed.id());
    }

    static void handleThanks(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Optional<Order> found = findOrder(q.get("id"));

        String body;
        if (found.isEmpty()) {
            body = """
                <section class="section narrow center">
                  <h2>Khong tim thay giao dich</h2>
                  <p class="lead-sm">Co the lien ket da het han. Quay lai trang chu de thu lai.</p>
                  <a class="btn btn-primary" href="/">Ve trang chu</a>
                </section>
                """;
        } else {
            Order o = found.get();
            boolean isProduct = "product".equals(o.kind());
            String tierBadgeClass = isProduct ? "tier-gold" : tierClass(o.tier());
            String headline = isProduct
                ? escape(o.buyerName()) + " da dat mua " + escape(o.itemName())
                : escape(o.buyerName()) + " da ung ho " + formatVND(o.amount());
            String noteLine = o.message().isEmpty()
                ? (isProduct ? "Cam on ban da tin tuong OWNSTYLE!" : "Cam on ban da dong hanh cung OWNSTYLE!")
                : "\u201C" + escape(o.message()) + "\u201D";

            body = """
                <section class="section narrow center">
                  <div class="thanks-badge %s">&#10003;</div>
                  <p class="section-eyebrow">CAM ON BAN</p>
                  <h2>%s</h2>
                  <p class="lead-sm">%s</p>
                  <div class="thanks-card">
                    <div class="thanks-row"><span>Ma giao dich</span><b>%s</b></div>
                    <div class="thanks-row"><span>So tien</span><b>%s</b></div>
                    <div class="thanks-row"><span>Phuong thuc</span><b>%s</b></div>
                  </div>
                  <p class="fine-print">Giao dich nhan qua chuyen khoan truc tiep, duoc SePay xac nhan tu dong
                     (hoac ban tu xac nhan neu chuyen qua ZaloPay).</p>
                  <div class="hero-actions">
                    <a class="btn btn-primary" href="/vat-pham">Xem them vat pham</a>
                    <a class="btn btn-ghost" href="/nguoi-ung-ho">Xem buc tuong ung ho</a>
                  </div>
                </section>
                """.formatted(tierBadgeClass, headline, noteLine, o.id(), formatVND(o.amount()),
                        escape(o.method().isEmpty() ? "Dang cho" : o.method()));
        }

        sendHtml(ex, 200, renderPage(SITE_NAME + " — Cam on", body, "thanks"));
    }

    // =====================================================================
    // NGUOI UNG HO
    // =====================================================================

    static void handleSupporters(HttpExchange ex) throws IOException {
        List<Order> all = confirmedDonations();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.systemDefault());

        StringBuilder rows = new StringBuilder();
        if (all.isEmpty()) {
            rows.append("<tr><td colspan=\"5\" class=\"wall-empty\">Chua co du lieu.</td></tr>");
        } else {
            for (Order o : all) {
                rows.append("<tr>")
                    .append("<td><span class=\"badge ").append(tierClass(o.tier())).append("\">").append(o.tier()).append("</span></td>")
                    .append("<td>").append(escape(o.buyerName())).append("</td>")
                    .append("<td class=\"num\">").append(formatVND(o.amount())).append("</td>")
                    .append("<td>").append(escape(o.method())).append("</td>")
                    .append("<td class=\"muted\">").append(fmt.format(o.time())).append("</td>")
                    .append("</tr>");
                if (!o.message().isEmpty()) {
                    rows.append("<tr class=\"msg-row\"><td></td><td colspan=\"4\">\u201C").append(escape(o.message())).append("\u201D</td></tr>");
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
              <div class="tier-cta"><a class="btn btn-primary" href="/ung-ho">Tro thanh nguoi ung ho tiep theo</a></div>
            </section>
            """.formatted(all.size(), formatVND(totalRaised()), rows.toString());

        sendHtml(ex, 200, renderPage(SITE_NAME + " — Nguoi ung ho", body, "supporters"));
    }

    // =====================================================================
    // SEPAY WEBHOOK — tu dong xac nhan giao dich ngan hang that
    // Tai lieu: https://docs.sepay.vn/tich-hop-webhooks.html
    // =====================================================================

    static void handleSepayWebhook(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 405, "{\"success\":false}"); return; }

        if (SEPAY_API_KEY != null && !SEPAY_API_KEY.isBlank()
                && !SEPAY_API_KEY.equals("DAT_API_KEY_WEBHOOK_CUA_BAN_O_DAY")) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Apikey " + SEPAY_API_KEY;
            if (auth == null || !auth.trim().equals(expected)) { sendJson(ex, 401, "{\"success\":false}"); return; }
        }

        byte[] raw = ex.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);

        String idStr = jsonField(body, "id");
        String transferType = jsonField(body, "transferType");
        String content = jsonField(body, "content");

        Long webhookId = null;
        try { if (idStr != null) webhookId = Long.parseLong(idStr); } catch (NumberFormatException ignored) {}

        if (webhookId != null && !PROCESSED_WEBHOOK_IDS.add(webhookId)) { sendJson(ex, 200, "{\"success\":true}"); return; }

        if ("in".equalsIgnoreCase(transferType) && content != null) {
            Matcher m = Pattern.compile("OS\\d+", Pattern.CASE_INSENSITIVE).matcher(content);
            if (m.find()) {
                String orderId = m.group().toUpperCase();
                Optional<Order> found = findOrder(orderId);
                found.ifPresent(order -> confirmOrder(order, "Chuyen khoan qua SePay (tu dong xac nhan)"));
            }
        }

        sendJson(ex, 200, "{\"success\":true}");
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String jsonField(String json, String key) {
        if (json == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9]+(?:\\.[0-9]+)?)|null)");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        if (m.group(1) != null) return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        return m.group(2);
    }

    static void handleZaloQrImage(HttpExchange ex) throws IOException {
        File f = new File(ZALOPAY_QR_FILE);
        if (!f.exists()) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(f.toPath());
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void handleCss(HttpExchange ex) throws IOException {
        byte[] bytes = CSS.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/css; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String vietQrUrl(long amount, String addInfo) {
        String acc = URLEncoder.encode(BANK_ACCOUNT_NO, StandardCharsets.UTF_8);
        String bank = URLEncoder.encode(BANK_BIN, StandardCharsets.UTF_8);
        String des = URLEncoder.encode(addInfo, StandardCharsets.UTF_8);
        return "https://vietqr.app/img?acc=" + acc + "&bank=" + bank + "&amount=" + amount + "&des=" + des + "&template=compact";
    }

    // =====================================================================
    // LAYOUT DUNG CHUNG
    // =====================================================================

    static String renderPage(String title, String content, String active) { return renderPage(title, content, active, ""); }

    static String renderPage(String title, String content, String active, String extraHead) {
        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <link rel="stylesheet" href="/assets/style.css">
              %s
            </head>
            <body>
              <header class="topbar">
                <a class="brand" href="/"><span class="brand-mark">OS</span>%s</a>
                <nav>
                  <a href="/" class="%s">Trang chu</a>
                  <a href="/gioi-thieu" class="%s">Gioi thieu</a>
                  <a href="/tin-tuc" class="%s">Tin tuc</a>
                  <a href="/vat-pham" class="%s">Vat pham</a>
                  <a href="/ung-ho" class="nav-cta %s">Ung ho</a>
                </nav>
              </header>
              <main>%s</main>
              <footer class="footer">
                <p>%s — website ca nhan viet hoan toan bang Java (com.sun.net.httpserver), khong dung framework ngoai.</p>
                <p class="footer-links"><a href="/nguoi-ung-ho">Nguoi ung ho</a></p>
              </footer>
            </body>
            </html>
            """.formatted(
                escape(title), extraHead, SITE_NAME,
                active.equals("home") ? "active" : "",
                active.equals("about") ? "active" : "",
                active.equals("news") ? "active" : "",
                active.equals("shop") ? "active" : "",
                active.equals("donate") ? "active" : "",
                content, SITE_NAME
            );
    }

    // =====================================================================
    // TIEN ICH
    // =====================================================================

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void send404(HttpExchange ex) throws IOException {
        sendHtml(ex, 404, renderPage(SITE_NAME + " — Khong tim thay", "<section class=\"section narrow center\"><h2>404 — Khong tim thay trang</h2><a class=\"btn btn-primary\" href=\"/\">Ve trang chu</a></section>", ""));
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
            map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                     URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    static Map<String, String> parseForm(String body) { return parseQuery(body); }

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
        return sb.reverse().toString() + "\u20AB";
    }

    // =====================================================================
    // CSS (sinh boi Java, khong can file rieng)
    // =====================================================================

    static final String CSS = """
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
          background: radial-gradient(1200px 600px at 15% -10%, #1a1f2e 0%, var(--bg) 55%) fixed,
                      radial-gradient(900px 500px at 100% 20%, #16241f 0%, transparent 60%) fixed;
          color: var(--text);
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
          line-height: 1.55;
        }
        a { color: inherit; text-decoration: none; }
        h1, h2, h3 { font-family: Georgia, "Times New Roman", serif; letter-spacing: 0.2px; margin: 0.2em 0 0.4em; }
        h1 { font-size: clamp(2.1rem, 5vw, 3.4rem); }
        h2 { font-size: clamp(1.5rem, 3vw, 2.1rem); }
        h3 { font-size: 1.1rem; }

        .topbar {
          display: flex; align-items: center; justify-content: space-between;
          padding: 18px 6vw; position: sticky; top: 0; z-index: 20;
          background: rgba(11,13,18,0.82); backdrop-filter: blur(10px);
          border-bottom: 1px solid var(--border);
        }
        .brand { display: flex; align-items: center; gap: 10px; font-family: Georgia, serif; font-weight: 700; letter-spacing: 3px; font-size: 1.05rem; color: var(--gold); }
        .brand-mark { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 9px; background: linear-gradient(135deg, var(--gold), #a9822f); color: #1a1406; font-size: 0.82rem; font-weight: 800; letter-spacing: 0; }
        .topbar nav { display: flex; align-items: center; gap: 26px; }
        .topbar nav a { color: var(--muted); font-size: 0.92rem; padding-bottom: 4px; border-bottom: 2px solid transparent; transition: color .2s, border-color .2s; }
        .topbar nav a:hover, .topbar nav a.active { color: var(--text); border-color: var(--gold); }
        .topbar nav a.nav-cta { color: #1a1406; background: linear-gradient(135deg, var(--gold), #c99a3e); padding: 8px 18px; border-radius: 999px; border-bottom: none; font-weight: 600; }
        .topbar nav a.nav-cta:hover { filter: brightness(1.08); }

        main { max-width: 1100px; margin: 0 auto; padding: 0 6vw; }

        .hero { position: relative; padding: 84px 0 64px; overflow: hidden; }
        .hero-inner { position: relative; z-index: 2; max-width: 640px; }
        .eyebrow, .section-eyebrow { color: var(--gold); letter-spacing: 3px; font-size: 0.78rem; font-weight: 600; text-transform: uppercase; margin: 0 0 10px; }
        .lead { color: var(--muted); font-size: 1.08rem; max-width: 540px; }
        .lead-sm { color: var(--muted); font-size: 0.98rem; }
        .accent { color: var(--gold); }
        .hero-glow {
          position: absolute; right: -140px; top: -100px; width: 520px; height: 520px; border-radius: 50%;
          background: radial-gradient(circle, rgba(216,180,92,0.26), rgba(63,191,160,0.10) 55%, transparent 72%);
          filter: blur(10px); z-index: 1;
        }
        .hero-actions { display: flex; gap: 14px; margin: 26px 0 36px; flex-wrap: wrap; }
        .btn { display: inline-block; padding: 13px 26px; border-radius: 999px; font-size: 0.95rem; font-weight: 600; transition: transform .15s, box-shadow .15s, background .15s; border: 1px solid transparent; }
        .btn-sm { padding: 9px 18px; font-size: 0.85rem; }
        .btn-primary { background: linear-gradient(135deg, var(--gold), #c99a3e); color: #1a1406; box-shadow: 0 8px 24px -8px rgba(216,180,92,0.55); }
        .btn-primary:hover { transform: translateY(-2px); box-shadow: 0 12px 28px -8px rgba(216,180,92,0.65); }
        .btn-ghost { border-color: var(--border); color: var(--text); }
        .btn-ghost:hover { border-color: var(--gold); color: var(--gold); }
        .btn-block { display: block; width: 100%; text-align: center; padding: 15px; margin-top: 8px; }
        .hero-stats { display: flex; gap: 36px; flex-wrap: wrap; }
        .hero-stats > div { display: flex; flex-direction: column; }
        .stat-num { font-family: Georgia, serif; font-size: 1.6rem; color: var(--text); }
        .stat-label { color: var(--muted); font-size: 0.8rem; }

        .section { padding: 60px 0; border-top: 1px solid var(--border); }
        .section.narrow { max-width: 640px; margin: 0 auto; }
        .section.center { text-align: center; }
        .section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; flex-wrap: wrap; margin-bottom: 8px; }
        .link-more { color: var(--gold); font-size: 0.88rem; font-weight: 600; white-space: nowrap; }
        .link-more:hover { text-decoration: underline; }

        /* Gioi thieu */
        .value-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; margin: 30px 0; text-align: left; }
        .value-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 20px; }
        .value-icon { color: var(--gold); font-family: Georgia, serif; font-size: 1.4rem; }
        .value-card p { color: var(--muted); font-size: 0.86rem; margin-top: 6px; }

        /* Tin tuc */
        .news-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 18px; margin-top: 22px; }
        .news-grid-full { grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); }
        .news-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 22px; transition: transform .15s; }
        .news-card:hover { transform: translateY(-3px); }
        .news-tag { display: inline-block; color: var(--teal); border: 1px solid var(--teal); border-radius: 999px; padding: 3px 10px; font-size: 0.72rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 10px; }
        .news-date { color: var(--muted); font-size: 0.78rem; margin: 0 0 10px; }
        .news-summary { color: var(--muted); font-size: 0.88rem; }

        /* Vat pham */
        .product-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin-top: 22px; }
        .product-grid-full { grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
        .product-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 22px; border-top: 3px solid var(--muted); display: flex; flex-direction: column; gap: 8px; transition: transform .15s; }
        .product-card:hover { transform: translateY(-4px); }
        .product-card.tier-bronze { border-top-color: var(--bronze); } .product-card.tier-silver { border-top-color: var(--silver); }
        .product-card.tier-gold { border-top-color: var(--gold); } .product-card.tier-diamond { border-top-color: var(--diamond); }
        .product-thumb { width: 52px; height: 52px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: var(--bg-alt); border: 1px solid var(--border); font-family: Georgia, serif; font-weight: 700; color: var(--gold); font-size: 1rem; }
        .product-cat { color: var(--muted); font-size: 0.72rem; text-transform: uppercase; letter-spacing: 1px; }
        .product-desc { color: var(--muted); font-size: 0.86rem; flex: 1; }
        .product-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; gap: 10px; }
        .product-price { color: var(--gold); font-weight: 700; font-family: Georgia, serif; }

        /* Buc tuong ung ho */
        .tier-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 18px; margin-top: 22px; }
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

        .pay-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 18px; margin: 28px 0; text-align: left; }
        .pay-card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 22px; text-align: center; }
        .pay-card h3 { color: var(--gold); margin: 0 0 14px; }
        .qr-img { width: 100%; max-width: 220px; border-radius: 10px; background: #fff; padding: 8px; margin: 0 auto; display: block; }
        .qr-missing { width: 100%; max-width: 220px; aspect-ratio: 1; margin: 0 auto; display: flex; align-items: center; justify-content: center; text-align: center; background: var(--bg-alt); border: 1px dashed var(--border); border-radius: 10px; color: var(--muted); font-size: 0.82rem; padding: 12px; }
        .pay-meta { margin-top: 14px; color: var(--muted); font-size: 0.85rem; }
        .pay-meta b { color: var(--text); font-family: Georgia, serif; font-size: 1rem; }

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
        .footer-links { margin-top: 6px; }
        .footer-links a { color: var(--gold); }

        @media (max-width: 720px) {
          .topbar { padding: 16px 5vw; flex-wrap: wrap; gap: 12px; }
          .topbar nav { gap: 14px; flex-wrap: wrap; }
          .hero { padding: 54px 0 36px; }
        }
        """;
}
