import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pulls REAL, current list prices from public vendor APIs (no API key needed):
 *
 *  - Azure Retail Prices API (https://prices.azure.com/api/retail/prices): pay-as-you-go,
 *    1-year and 3-year reserved instance, and Spot prices for a VM size in a region, plus Blob
 *    storage and internet egress prices.
 *  - AWS Spot Instance Advisor dataset (https://spot-bid-advisor.s3.amazonaws.com/spot-advisor-data.json):
 *    the published monthly interruption frequency for the equivalent instance type. Azure does not
 *    publish eviction rates without a login, so this public dataset is used for interruption risk.
 */
final class PriceService {
    static final Duration TTL = Duration.ofHours(6);
    static final String AZURE_API = "https://prices.azure.com/api/retail/prices";
    static final String AWS_SPOT_ADVISOR = "https://spot-bid-advisor.s3.amazonaws.com/spot-advisor-data.json";

    /** Azure region -> nearest AWS region (for the interruption-frequency dataset). */
    static final LinkedHashMap<String, String[]> REGIONS = new LinkedHashMap<>();
    /** Azure VM size -> {label, vCPU, RAM GB, AWS equivalent}. */
    static final LinkedHashMap<String, String[]> SKUS = new LinkedHashMap<>();
    static final String[] CURRENCIES = {"USD", "INR", "EUR", "GBP"};

    static {
        REGIONS.put("eastus", new String[]{"East US (Virginia)", "us-east-1"});
        REGIONS.put("westus2", new String[]{"West US 2 (Washington)", "us-west-2"});
        REGIONS.put("centralindia", new String[]{"Central India (Pune)", "ap-south-1"});
        REGIONS.put("southindia", new String[]{"South India (Chennai)", "ap-south-1"});
        REGIONS.put("northeurope", new String[]{"North Europe (Ireland)", "eu-west-1"});
        REGIONS.put("westeurope", new String[]{"West Europe (Netherlands)", "eu-central-1"});
        REGIONS.put("uksouth", new String[]{"UK South (London)", "eu-west-2"});
        REGIONS.put("southeastasia", new String[]{"Southeast Asia (Singapore)", "ap-southeast-1"});
        REGIONS.put("japaneast", new String[]{"Japan East (Tokyo)", "ap-northeast-1"});
        REGIONS.put("australiaeast", new String[]{"Australia East (Sydney)", "ap-southeast-2"});

        SKUS.put("Standard_D2s_v5", new String[]{"D2s v5 - general purpose", "2", "8", "m6i.large"});
        SKUS.put("Standard_D4s_v5", new String[]{"D4s v5 - general purpose", "4", "16", "m6i.xlarge"});
        SKUS.put("Standard_D8s_v5", new String[]{"D8s v5 - general purpose", "8", "32", "m6i.2xlarge"});
        SKUS.put("Standard_E2s_v5", new String[]{"E2s v5 - memory optimised", "2", "16", "r6i.large"});
        SKUS.put("Standard_E4s_v5", new String[]{"E4s v5 - memory optimised", "4", "32", "r6i.xlarge"});
        SKUS.put("Standard_F2s_v2", new String[]{"F2s v2 - compute optimised", "2", "4", "c5.large"});
        SKUS.put("Standard_F4s_v2", new String[]{"F4s v2 - compute optimised", "4", "8", "c5.xlarge"});
        SKUS.put("Standard_B2s", new String[]{"B2s - burstable (no Spot)", "2", "4", "t3.medium"});
    }

    static final class Source {
        final String item, value, unit, source, retrieved, url;
        Source(String item, String value, String unit, String source, String retrieved, String url) {
            this.item = item; this.value = value; this.unit = unit; this.source = source; this.retrieved = retrieved; this.url = url;
        }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item); m.put("value", value); m.put("unit", unit);
            m.put("source", source); m.put("retrieved", retrieved); m.put("url", url);
            return m;
        }
    }

    static final class PriceSheet {
        String region, regionLabel, sku, skuLabel, currency, awsRegion, awsType;
        double payg = Double.NaN, reserved1y = Double.NaN, reserved3y = Double.NaN, spot = Double.NaN; // per VM-hour
        double storagePerGbMonth = Double.NaN;
        final List<double[]> egressTiers = new ArrayList<>(); // {fromGb, pricePerGb}
        String spotBand = "unknown";
        double spotMonthlyInterruptionRate = Double.NaN;
        double awsSpotSavingsPct = Double.NaN;
        Instant fetchedAt;
        boolean stale;
        final List<Source> sources = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        double reservedHourly(int termYears) { return termYears == 3 ? reserved3y : reserved1y; }

        double egressCost(double gb) {
            List<double[]> t = new ArrayList<>(egressTiers);
            t.sort(Comparator.comparingDouble(a -> a[0]));
            double cost = 0;
            for (int k = 0; k < t.size(); k++) {
                double from = t.get(k)[0];
                double to = k + 1 < t.size() ? t.get(k + 1)[0] : Double.MAX_VALUE;
                if (gb <= from) break;
                cost += (Math.min(gb, to) - from) * t.get(k)[1];
            }
            return cost;
        }
    }

    private static final Map<String, PriceSheet> SHEETS = new HashMap<>();

    static synchronized PriceSheet load(String region, String sku, String currency) throws IOException {
        if (!REGIONS.containsKey(region)) throw new IOException("Unsupported region " + region);
        if (!SKUS.containsKey(sku)) throw new IOException("Unsupported VM size " + sku);
        if (!Arrays.asList(CURRENCIES).contains(currency)) throw new IOException("Unsupported currency " + currency);
        String key = region + "|" + sku + "|" + currency;
        PriceSheet cached = SHEETS.get(key);
        if (cached != null && cached.fetchedAt.plus(TTL).isAfter(Instant.now())) return cached;

        PriceSheet p = new PriceSheet();
        p.region = region; p.regionLabel = REGIONS.get(region)[0];
        p.sku = sku; p.skuLabel = SKUS.get(sku)[0]; p.currency = currency;
        p.awsRegion = REGIONS.get(region)[1]; p.awsType = SKUS.get(sku)[3];
        p.fetchedAt = Instant.now();

        loadVmPrices(p);
        loadStorage(p);
        loadEgress(p);
        loadSpotRisk(p);

        if (Double.isNaN(p.payg)) throw new IOException("Azure returned no pay-as-you-go price for " + sku + " in " + region);
        SHEETS.put(key, p);
        return p;
    }

    private static List<Map<String, Object>> azureQuery(PriceSheet p, String filter, String label) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        String url = AZURE_API + "?currencyCode=" + Http.enc("'" + p.currency + "'") + "&$filter=" + Http.enc(filter);
        int pages = 0;
        while (url != null && !url.isEmpty() && pages++ < 10) {
            Http.Fetched f = Http.get(url, TTL);
            track(p, f);
            Map<String, Object> root = Json.obj(Json.parse(f.body));
            for (Object o : Json.arr(root.get("Items"))) items.add(Json.obj(o));
            Object next = root.get("NextPageLink");
            url = next == null ? null : next.toString();
        }
        return items;
    }

    private static void track(PriceSheet p, Http.Fetched f) {
        if (f.fetchedAt.isBefore(p.fetchedAt)) p.fetchedAt = f.fetchedAt;
        if (f.stale) p.stale = true;
    }

    private static void loadVmPrices(PriceSheet p) throws IOException {
        String filter = "serviceName eq 'Virtual Machines' and armRegionName eq '" + p.region + "' and armSkuName eq '" + p.sku + "'";
        List<Map<String, Object>> items = azureQuery(p, filter, "VM");
        String when = p.fetchedAt.toString();
        String src = "Azure Retail Prices API";
        String link = "https://prices.azure.com/api/retail/prices?$filter=" + Http.enc(filter);
        for (Map<String, Object> it : items) {
            String type = Json.str(it, "type");
            String meter = Json.str(it, "meterName");
            String product = Json.str(it, "productName");
            if (product.contains("Windows") || meter.contains("Low Priority")) continue; // Linux, standard VMs only
            double price = Json.num(it, "unitPrice");
            if (type.equals("Consumption") && meter.endsWith("Spot")) {
                p.spot = price;
                p.sources.add(new Source("Spot VM (" + meter + ")", fmt(price, p.currency), "per VM-hour", src + " - meterId " + Json.str(it, "meterId"), when, link));
            } else if (type.equals("Consumption")) {
                p.payg = price;
                p.sources.add(new Source("Pay-as-you-go VM (" + meter + ")", fmt(price, p.currency), "per VM-hour", src + " - meterId " + Json.str(it, "meterId"), when, link));
            } else if (type.equals("Reservation")) {
                String term = Json.str(it, "reservationTerm");
                double hours = term.startsWith("3") ? 3 * 8760.0 : 8760.0;
                double hourly = price / hours;
                if (term.startsWith("3")) p.reserved3y = hourly; else p.reserved1y = hourly;
                p.sources.add(new Source("Reserved " + term + " (" + meter + ")", fmt(hourly, p.currency),
                        "per VM-hour (" + fmt(price, p.currency) + " upfront / " + (int) hours + " h)", src, when, link));
            }
        }
        if (Double.isNaN(p.spot)) p.warnings.add("Azure does not sell " + p.sku + " as a Spot VM in " + p.region + ", so the Spot strategies are unavailable.");
        if (Double.isNaN(p.reserved1y)) p.warnings.add("No reserved-instance price was published for " + p.sku + " in " + p.region + ".");
    }

    private static void loadStorage(PriceSheet p) throws IOException {
        String filter = "serviceName eq 'Storage' and armRegionName eq '" + p.region + "' and productName eq 'General Block Blob v2' and skuName eq 'Hot LRS' and meterName eq 'Hot LRS Data Stored'";
        List<Map<String, Object>> items = azureQuery(p, filter, "Storage");
        for (Map<String, Object> it : items) {
            if (Json.num(it, "tierMinimumUnits") == 0.0) {
                p.storagePerGbMonth = Json.num(it, "unitPrice");
                p.sources.add(new Source("Blob storage, Hot LRS (first 50 TB)", fmt(p.storagePerGbMonth, p.currency), "per GB-month",
                        "Azure Retail Prices API", p.fetchedAt.toString(), "https://prices.azure.com/api/retail/prices?$filter=" + Http.enc(filter)));
            }
        }
        if (Double.isNaN(p.storagePerGbMonth)) { p.storagePerGbMonth = 0; p.warnings.add("No storage price returned; storage cost set to 0."); }
    }

    private static void loadEgress(PriceSheet p) throws IOException {
        String filter = "serviceName eq 'Bandwidth' and armRegionName eq '" + p.region + "' and meterName eq 'Standard Data Transfer Out' and productName eq 'Rtn Preference: MGN'";
        List<Map<String, Object>> items = azureQuery(p, filter, "Egress");
        StringBuilder tiers = new StringBuilder();
        items.sort(Comparator.comparingDouble(it -> Json.num(it, "tierMinimumUnits")));
        for (Map<String, Object> it : items) {
            double from = Json.num(it, "tierMinimumUnits"), price = Json.num(it, "unitPrice");
            p.egressTiers.add(new double[]{from, price});
            if (tiers.length() > 0) tiers.append("; ");
            tiers.append(">").append((long) from).append(" GB: ").append(fmt(price, p.currency));
        }
        if (p.egressTiers.isEmpty()) p.warnings.add("No egress price returned; egress cost set to 0.");
        else p.sources.add(new Source("Internet egress (tiered)", tiers.toString(), "per GB", "Azure Retail Prices API",
                p.fetchedAt.toString(), "https://prices.azure.com/api/retail/prices?$filter=" + Http.enc(filter)));
    }

    private static void loadSpotRisk(PriceSheet p) {
        try {
            Http.Fetched f = Http.get(AWS_SPOT_ADVISOR, TTL);
            track(p, f);
            Map<String, Object> root = Json.obj(Json.parse(f.body));
            List<Object> ranges = Json.arr(root.get("ranges"));
            Map<String, Object> region = Json.obj(Json.obj(root.get("spot_advisor")).get(p.awsRegion));
            Map<String, Object> entry = region == null ? null : Json.obj(Json.obj(region.get("Linux")).get(p.awsType));
            if (entry == null) throw new IOException("no entry for " + p.awsType);
            int r = (int) Json.num(entry, "r");
            p.spotBand = Json.str(Json.obj(ranges.get(r)), "label");
            double[] midpoints = {0.025, 0.075, 0.125, 0.175, 0.25};
            p.spotMonthlyInterruptionRate = midpoints[Math.min(r, 4)];
            p.awsSpotSavingsPct = Json.num(entry, "s");
            p.sources.add(new Source("Spot interruption frequency (" + p.awsType + ", " + p.awsRegion + ")",
                    p.spotBand + " per month (model uses " + (int) Math.round(p.spotMonthlyInterruptionRate * 1000) / 10.0 + "%)",
                    "share of Spot instances interrupted per month", "AWS Spot Instance Advisor dataset", f.fetchedAt.toString(), AWS_SPOT_ADVISOR));
        } catch (Exception e) {
            p.spotMonthlyInterruptionRate = 0.075;
            p.spotBand = "5-10% (fallback)";
            p.warnings.add("Spot interruption data unavailable (" + e.getMessage() + "); assuming 7.5% per month.");
        }
    }

    static String symbol(String currency) {
        switch (currency) {
            case "INR": return "₹";
            case "EUR": return "€";
            case "GBP": return "£";
            default: return "$";
        }
    }

    static String fmt(double v, String currency) {
        if (Double.isNaN(v)) return "n/a";
        return symbol(currency) + (Math.abs(v) < 1 ? String.format("%.5f", v) : String.format("%,.4f", v));
    }
}
