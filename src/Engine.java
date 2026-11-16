import java.util.*;

/**
 * FinOps simulation engine. All rates come from a live {@link PriceService.PriceSheet}; the
 * demand curve comes from a real {@link WorkloadService.Workload}. Nothing here is hard-coded
 * pricing - only the user's own operational inputs (fleet size, storage, egress, restart time).
 */
final class Engine {

    static final class Inputs {
        double avgFleet = 10;          // average number of VMs the service needs
        double demandMultiplier = 1.0; // what-if growth
        double priceMultiplier = 1.0;  // what-if price change / negotiated discount
        double storageGb = 500;
        double egressGb = 300;
        int termYears = 1;             // reservation term: 1 or 3 years
        boolean interruptionTolerant = true;
        double restartMinutes = 5;     // time to replace an evicted Spot VM
        double checkpointMinutes = 30; // work lost on eviction = half the checkpoint interval
        double impactPerInterruption = 0; // optional business cost per eviction (user's own figure)
    }

    enum Strategy {
        PAYG("Pay-as-you-go"), RESERVED("Reserved instances"), SPOT("Spot (interruptible)"), HYBRID("Hybrid: Reserved + Spot");
        final String label;
        Strategy(String label) { this.label = label; }
    }

    static final class Result {
        Strategy strategy;
        boolean available = true, eligible = true;
        String unavailableReason = "";
        double total, compute, shared, rework, impact, variability, interruptions, savingsVsPayg;
        int reservedVms;
        double reservationUtilisation = Double.NaN, spotShare;
        double[] daily;
        String effect = "", reason = "";

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", strategy.name()); m.put("label", strategy.label);
            m.put("available", available); m.put("eligible", eligible); m.put("unavailableReason", unavailableReason);
            m.put("total", total); m.put("compute", compute); m.put("shared", shared);
            m.put("rework", rework); m.put("impact", impact); m.put("variability", variability);
            m.put("interruptions", interruptions); m.put("savingsVsPayg", savingsVsPayg);
            m.put("reservedVms", reservedVms); m.put("reservationUtilisation", reservationUtilisation);
            m.put("daily", daily); m.put("effect", effect); m.put("reason", reason);
            return m;
        }
    }

    static double[] demandHours(WorkloadService.Workload w, Inputs in) {
        double[] v = w.values.length > 30 ? Arrays.copyOfRange(w.values, w.values.length - 30, w.values.length) : w.values;
        double mean = Arrays.stream(v).average().orElse(0);
        if (mean <= 0) throw new IllegalArgumentException("The selected workload has no traffic in this period.");
        double[] h = new double[v.length];
        for (int i = 0; i < v.length; i++) h[i] = 24.0 * in.avgFleet * (v[i] / mean) * in.demandMultiplier;
        return h;
    }

    static double sharedCost(PriceService.PriceSheet p, Inputs in, int days) {
        double monthFraction = days / 30.0;
        return in.storageGb * p.storagePerGbMonth * monthFraction + p.egressCost(in.egressGb * monthFraction);
    }

    static List<Result> simulateAll(PriceService.PriceSheet p, double[] hours, Inputs in) {
        List<Result> out = new ArrayList<>();
        for (Strategy s : Strategy.values()) out.add(simulate(p, s, hours, in));
        double payg = out.get(0).total;
        for (Result r : out) r.savingsVsPayg = payg - r.total;
        return out;
    }

    static Result best(List<Result> results) {
        Result best = null;
        for (Result r : results)
            if (r.available && r.eligible && (best == null || r.total < best.total)) best = r;
        return best;
    }

    static Result simulate(PriceService.PriceSheet p, Strategy s, double[] hours, Inputs in) {
        int days = hours.length;
        double pm = in.priceMultiplier;
        double payg = p.payg * pm, res = p.reservedHourly(in.termYears) * pm, spot = p.spot * pm;
        double shared = sharedCost(p, in, days);
        double totalHours = Arrays.stream(hours).sum();
        Result r = new Result();
        r.strategy = s;
        r.shared = shared;
        r.daily = new double[days];

        if ((s == Strategy.RESERVED || s == Strategy.HYBRID) && Double.isNaN(res)) {
            r.available = false; r.unavailableReason = "No reserved price published for this VM size/region.";
        }
        if ((s == Strategy.SPOT || s == Strategy.HYBRID) && Double.isNaN(spot)) {
            r.available = false; r.unavailableReason = "Azure does not offer this VM size as Spot in this region.";
        }
        if ((s == Strategy.SPOT || s == Strategy.HYBRID) && !in.interruptionTolerant) {
            r.eligible = false; r.unavailableReason = "Workload marked as NOT interruption-tolerant.";
        }
        if (!r.available) { r.total = Double.NaN; r.effect = r.unavailableReason; return r; }

        double reworkHoursPerEviction = (in.restartMinutes + in.checkpointMinutes / 2.0) / 60.0;

        switch (s) {
            case PAYG: {
                for (int i = 0; i < days; i++) r.daily[i] = hours[i] * payg;
                r.compute = totalHours * payg;
                r.effect = "No commitment, no interruptions";
                r.reason = "Maximum flexibility, but every hour is billed at the full on-demand rate.";
                break;
            }
            case RESERVED: {
                int c = optimalReservation(hours, payg, res, Double.NaN);
                applyReserved(r, hours, c, payg, res, Double.NaN, 0);
                double util = r.reservationUtilisation;
                if (c == 0) {
                    r.effect = "Demand too variable - no VM is busy enough to justify a reservation";
                    r.reason = String.format("A reservation only pays off when it is used more than %.0f%% of the time.", 100 * res / payg);
                    break;
                }
                r.effect = String.format("%d VMs reserved for %d-yr term, %.0f%% utilised; peaks on pay-as-you-go", c, in.termYears, util * 100);
                r.reason = String.format("A reservation pays off when it is used more than %.0f%% of the time (reserved/on-demand price ratio). "
                        + "The optimiser reserved the baseline that is used %.0f%% of the time.", 100 * res / payg, util * 100);
                break;
            }
            case SPOT: {
                double avgFleet = totalHours / (24.0 * days);
                r.interruptions = avgFleet * p.spotMonthlyInterruptionRate * days / 30.0;
                r.rework = r.interruptions * reworkHoursPerEviction * spot;
                r.impact = r.interruptions * in.impactPerInterruption;
                for (int i = 0; i < days; i++)
                    r.daily[i] = hours[i] * spot + (r.rework + r.impact) * hours[i] / totalHours;
                r.compute = totalHours * spot;
                r.spotShare = 1;
                r.effect = String.format("~%.1f evictions/month (%s rate); %.1f VM-hours of rework", r.interruptions, p.spotBand, r.interruptions * reworkHoursPerEviction);
                r.reason = "Spot capacity is the cheapest rate but can be reclaimed at short notice; rework from evictions is included.";
                break;
            }
            case HYBRID: {
                int c = optimalReservation(hours, payg, res, spot);
                double overflowHours = 0;
                for (double h : hours) overflowHours += Math.max(0, h - c * 24.0);
                double overflowFleet = overflowHours / (24.0 * days);
                r.interruptions = overflowFleet * p.spotMonthlyInterruptionRate * days / 30.0;
                r.rework = r.interruptions * reworkHoursPerEviction * spot;
                r.impact = r.interruptions * in.impactPerInterruption;
                applyReserved(r, hours, c, payg, res, spot, r.rework + r.impact);
                r.spotShare = totalHours == 0 ? 0 : overflowHours / totalHours;
                if (c == 0) {
                    r.effect = String.format("Spot is cheaper than any reservation, so hybrid = 100%% Spot; ~%.1f evictions/month", r.interruptions);
                    r.reason = "Spot already undercuts the reserved rate, so reserving a baseline would only add cost.";
                    break;
                }
                r.effect = String.format("%d VMs reserved (%s utilised) + %.0f%% of hours on Spot; ~%.1f evictions/month",
                        c, c == 0 ? "n/a" : Math.round(r.reservationUtilisation * 100) + "%", r.spotShare * 100, r.interruptions);
                r.reason = "Reserve the always-on baseline, burst above it on Spot - the standard FinOps pattern for variable demand.";
                break;
            }
        }
        for (int i = 0; i < days; i++) r.daily[i] += shared / days;
        r.total = r.compute + r.shared + r.rework + r.impact;
        r.variability = stdDev(r.daily);
        return r;
    }

    /** Daily-hours cost when c VMs are reserved; overflow at pay-as-you-go or Spot. */
    private static void applyReserved(Result r, double[] hours, int c, double payg, double res, double spot, double extra) {
        double totalHours = Arrays.stream(hours).sum();
        double reservedPerDay = c * 24.0;
        double used = 0, compute = 0;
        double overflowRate = Double.isNaN(spot) ? payg : spot;
        for (int i = 0; i < hours.length; i++) {
            double overflow = Math.max(0, hours[i] - reservedPerDay);
            used += Math.min(hours[i], reservedPerDay);
            r.daily[i] = reservedPerDay * res + overflow * overflowRate + (totalHours == 0 ? 0 : extra * hours[i] / totalHours);
            compute += reservedPerDay * res + overflow * overflowRate;
        }
        r.compute = compute;
        r.reservedVms = c;
        r.reservationUtilisation = c == 0 ? Double.NaN : used / (reservedPerDay * hours.length);
    }

    /** Brute-force the number of reserved VMs that minimises compute cost. */
    private static int optimalReservation(double[] hours, double payg, double res, double spot) {
        double overflowRate = Double.isNaN(spot) ? payg : spot;
        int maxVms = (int) Math.ceil(Arrays.stream(hours).max().orElse(0) / 24.0);
        int best = 0;
        double bestCost = Double.MAX_VALUE;
        for (int c = 0; c <= maxVms; c++) {
            double cost = 0;
            for (double h : hours) cost += c * 24.0 * res + Math.max(0, h - c * 24.0) * overflowRate;
            if (cost < bestCost - 1e-9) { bestCost = cost; best = c; }
        }
        return best;
    }

    static double stdDev(double[] v) {
        double mean = Arrays.stream(v).average().orElse(0);
        return Math.sqrt(Arrays.stream(v).map(x -> (x - mean) * (x - mean)).average().orElse(0));
    }

    // ------------------------------------------------------------------ reports

    static final String[][] DEPARTMENTS = {
            {"Engineering", "SPIKY", "0.45"},
            {"Analytics", "SEASONAL", "0.30"},
            {"Marketing", "STEADY", "0.25"},
    };

    /** Chargeback/showback: each department runs its own REAL workload on its share of the fleet. */
    static List<Map<String, Object>> chargeback(PriceService.PriceSheet p, Inputs in) throws java.io.IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] d : DEPARTMENTS) {
            WorkloadService.Workload w = WorkloadService.load(WorkloadService.Preset.valueOf(d[1]), null, null);
            Inputs di = copy(in);
            double share = Double.parseDouble(d[2]);
            di.avgFleet = Math.max(1, in.avgFleet * share);
            di.storageGb = in.storageGb * share;
            di.egressGb = in.egressGb * share;
            List<Result> res = simulateAll(p, demandHours(w, di), di);
            Result b = best(res);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("department", d[0]);
            m.put("workload", w.name);
            m.put("pattern", w.detectedPattern);
            m.put("share", share);
            m.put("fleet", di.avgFleet);
            m.put("strategy", b.strategy.label);
            m.put("cost", b.total);
            m.put("paygCost", res.get(0).total);
            m.put("savings", res.get(0).total - b.total);
            rows.add(m);
        }
        return rows;
    }

    static Map<String, Object> sensitivity(PriceService.PriceSheet p, WorkloadService.Workload w, Inputs in) {
        double[] demandF = {0.8, 1.0, 1.2};
        double[] priceF = {0.8, 1.0, 1.2};
        List<Object> grid = new ArrayList<>();
        for (double pf : priceF) {
            List<Object> row = new ArrayList<>();
            for (double df : demandF) {
                Inputs x = copy(in);
                x.demandMultiplier = in.demandMultiplier * df;
                x.priceMultiplier = in.priceMultiplier * pf;
                Result b = best(simulateAll(p, demandHours(w, x), x));
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("strategy", b.strategy.label);
                cell.put("total", b.total);
                row.add(cell);
            }
            grid.add(row);
        }
        // Spot risk sensitivity: how bad can interruptions get before Spot stops being worth it?
        List<Object> risk = new ArrayList<>();
        double[] rates = {0.025, 0.075, 0.125, 0.175, 0.25, 0.5};
        double original = p.spotMonthlyInterruptionRate;
        for (double rate : rates) {
            p.spotMonthlyInterruptionRate = rate;
            List<Result> rs = simulateAll(p, demandHours(w, in), in);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rate", rate);
            m.put("spot", rs.get(2).total);
            m.put("hybrid", rs.get(3).total);
            m.put("reserved", rs.get(1).total);
            m.put("payg", rs.get(0).total);
            risk.add(m);
        }
        p.spotMonthlyInterruptionRate = original;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demandFactors", demandF);
        m.put("priceFactors", priceF);
        m.put("grid", grid);
        m.put("spotRisk", risk);
        return m;
    }

    static Inputs copy(Inputs a) {
        Inputs b = new Inputs();
        b.avgFleet = a.avgFleet; b.demandMultiplier = a.demandMultiplier; b.priceMultiplier = a.priceMultiplier;
        b.storageGb = a.storageGb; b.egressGb = a.egressGb; b.termYears = a.termYears;
        b.interruptionTolerant = a.interruptionTolerant; b.restartMinutes = a.restartMinutes;
        b.checkpointMinutes = a.checkpointMinutes; b.impactPerInterruption = a.impactPerInterruption;
        return b;
    }

    /** Runs everything and returns one JSON-ready report (used by the web dashboard and the Swing GUI). */
    static Map<String, Object> report(PriceService.PriceSheet p, WorkloadService.Workload w, Inputs in, boolean withChargeback) throws java.io.IOException {
        double[] hours = demandHours(w, in);
        List<Result> results = simulateAll(p, hours, in);
        Result best = best(results);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generatedAt", java.time.Instant.now().toString());

        Map<String, Object> prices = new LinkedHashMap<>();
        prices.put("region", p.region); prices.put("regionLabel", p.regionLabel);
        prices.put("sku", p.sku); prices.put("skuLabel", p.skuLabel); prices.put("currency", p.currency);
        prices.put("symbol", PriceService.symbol(p.currency));
        prices.put("payg", p.payg); prices.put("reserved1y", p.reserved1y); prices.put("reserved3y", p.reserved3y); prices.put("spot", p.spot);
        prices.put("storagePerGbMonth", p.storagePerGbMonth);
        prices.put("spotBand", p.spotBand); prices.put("spotMonthlyInterruptionRate", p.spotMonthlyInterruptionRate);
        prices.put("awsEquivalent", p.awsType + " in " + p.awsRegion);
        prices.put("fetchedAt", p.fetchedAt.toString()); prices.put("stale", p.stale);
        List<Object> src = new ArrayList<>();
        for (PriceService.Source s : p.sources) src.add(s.toMap());
        prices.put("sources", src);
        prices.put("warnings", p.warnings);
        m.put("prices", prices);

        Map<String, Object> wl = w.toMap();
        wl.put("vmHours", hours);
        m.put("workload", wl);

        List<Object> rs = new ArrayList<>();
        for (Result r : results) rs.add(r.toMap());
        m.put("results", rs);

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("strategy", best.strategy.label);
        rec.put("key", best.strategy.name());
        rec.put("total", best.total);
        rec.put("savingsVsPayg", best.savingsVsPayg);
        rec.put("savingsPct", results.get(0).total == 0 ? 0 : 100 * best.savingsVsPayg / results.get(0).total);
        rec.put("text", recommendationText(p, w, in, results, best));
        Result guaranteed = bestGuaranteed(results);
        rec.put("guaranteedStrategy", guaranteed.strategy.label);
        rec.put("guaranteedTotal", guaranteed.total);
        rec.put("guaranteedSavingsVsPayg", guaranteed.savingsVsPayg);
        m.put("recommendation", rec);

        m.put("sensitivity", sensitivity(p, w, in));
        if (withChargeback) m.put("chargeback", chargeback(p, in));
        return m;
    }

    static String recommendationText(PriceService.PriceSheet p, WorkloadService.Workload w, Inputs in, List<Result> results, Result best) {
        String sym = PriceService.symbol(p.currency);
        StringBuilder b = new StringBuilder();
        b.append(String.format("For the %s workload (%s - detected as %s), choose %s at about %s%,.2f for %d days.",
                w.preset.label, w.name, w.detectedPattern, best.strategy.label, sym, best.total, w.values.length > 30 ? 30 : w.values.length));
        if (best.savingsVsPayg > 0.005)
            b.append(String.format(" That saves %s%,.2f (%.1f%%) against pay-as-you-go.", sym, best.savingsVsPayg, 100 * best.savingsVsPayg / results.get(0).total));
        b.append(" ").append(best.reason);
        if (!in.interruptionTolerant) b.append(" Spot options were excluded because the workload is marked as not interruption-tolerant.");
        else if (best.strategy == Strategy.SPOT || best.strategy == Strategy.HYBRID) {
            Result g = bestGuaranteed(results);
            b.append(" Only use Spot if the application can checkpoint and survive VM evictions.");
            b.append(String.format(" If capacity must be guaranteed, the best choice is %s at %s%,.2f.", g.strategy.label, sym, g.total));
        }
        return b.toString();
    }

    /** Cheapest option that never gets evicted (pay-as-you-go or reserved). */
    static Result bestGuaranteed(List<Result> results) {
        Result payg = results.get(0), reserved = results.get(1);
        return reserved.available && reserved.total < payg.total ? reserved : payg;
    }
}
