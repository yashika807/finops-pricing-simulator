# FinOps Pricing Simulator v2: live data edition

Project 10, Cloud Computing (ODD Sem 2026).

Version 1 used fictional teaching prices ($0.120, $0.075, $0.045 and so on) and hard-coded demand arrays.
**Version 2 uses no fictional numbers.** Every price, every interruption rate and every demand curve is fetched
live from public APIs each time you run it. The only numbers you type in are your own operating details:
fleet size, storage, egress and restart time.

## Where the data comes from

| What | Live source | Notes |
|---|---|---|
| Pay-as-you-go VM price | Azure Retail Prices API `prices.azure.com/api/retail/prices` | Official Microsoft API, no key needed. Linux VMs. |
| Reserved instance price (1 yr / 3 yr) | Same API (`type = Reservation`) | Term price ÷ hours in the term = effective hourly rate |
| Spot (interruptible) price | Same API (meter "… Spot") | Some sizes (such as B-series) have no Spot offer. The app shows this. |
| Storage price | Same API, Blob Hot LRS, per GB-month | |
| Egress price | Same API, Bandwidth "Standard Data Transfer Out", tiered | The first 100 GB/month are free, as on the real bill |
| Spot interruption frequency | AWS Spot Instance Advisor dataset `spot-bid-advisor.s3.amazonaws.com/spot-advisor-data.json` | Azure only publishes eviction rates behind a login, so the equivalent AWS instance is used (D2s v5 is matched to m6i.large) |
| Workload demand curve | Wikimedia Pageviews REST API | Real measured daily traffic for the last 30 complete days |
| Your own workload | CSV upload (`date,value`) | For example an export from AWS Cost Explorer, Azure Cost Management or CloudWatch |

The UI shows every rate together with its API, meter ID and fetch time. Responses are cached for 6 hours in
`cache/`. If you are offline, the last cached copy is used and the dashboard says **OFFLINE: cached prices**.
The app never falls back to made-up numbers.

### Real workloads

| Preset | Real traffic used | Typical detected pattern |
|---|---|---|
| Steady | All English Wikipedia user traffic | Steady (about 3% daily variation) |
| Spiky | Today's trending Wikipedia article with the sharpest spike (chosen automatically) | Spiky |
| Seasonal | The "LinkedIn" article: a clear weekday/weekend business cycle | Seasonal (weekly cycle) |
| Custom article | Any Wikipedia article title you enter | Classified automatically |
| My usage CSV | Your own data | Classified automatically |

The pattern is **detected** from the data (peak ÷ median ratio, coefficient of variation, lag-7 autocorrelation)
instead of being assumed.

Traffic is converted to compute demand like this: `VM-hours(day) = 24 × average fleet × traffic(day) / mean traffic`.
The real traffic sets the shape of demand and your fleet size sets the scale.

## Pricing model

- **Pay-as-you-go** = Σ VM-hours × live on-demand rate
- **Reserved**: an optimiser tries every commitment size from 0 to the peak and picks the cheapest number of VMs to reserve
  (this replaces the old "80% of median" rule). Reserved hours are paid whether used or not, and overflow is billed at pay-as-you-go.
  The output shows reservation utilisation and the break-even utilisation (reserved rate ÷ on-demand rate).
- **Spot** = Σ VM-hours × live Spot rate + rework. Expected evictions = average fleet × the published monthly interruption rate.
  Each eviction costs (restart time + half the checkpoint interval) of re-done work.
- **Hybrid (new)**: reserve the baseline and burst on Spot. This is the standard FinOps pattern.
- **Shared** = storage GB × live storage rate + tiered live egress cost
- **Recommendation**: the cheapest *eligible* strategy. If the workload is not interruption-tolerant, Spot is excluded.
  A "guaranteed capacity" option (cheapest of pay-as-you-go and reserved) is always reported as well.
- **Extra reports**: chargeback/showback (each department runs its own real workload), a ±20% demand × price
  sensitivity grid, and Spot-risk sensitivity (cost as the eviction rate rises).

## How to run

You need Java 17 or later. There are no third-party libraries.

```bash
cd FinOpsPricingSimulator
javac -d out src/*.java
java -cp out FinOpsPricingSimulator            # Swing desktop app (as required by the course)
java -cp out FinOpsPricingSimulator --web      # live web dashboard -> http://localhost:8080
java -cp out FinOpsPricingSimulator --self-test
```

`./run.sh`, `./run.sh web` and `./run.sh test` do the same things.

## Files

- `src/FinOpsPricingSimulator.java`: Swing GUI and entry point
- `src/PriceService.java`: live Azure prices and AWS Spot interruption data
- `src/WorkloadService.java`: live Wikimedia traffic, CSV import and pattern detection
- `src/Engine.java`: simulation, reservation optimiser, chargeback and sensitivity
- `src/WebServer.java`: JSON API and the web dashboard server
- `src/Http.java`, `src/Json.java`: HTTP client with cache, and a small JSON parser
- `web/index.html`: live dashboard (Chart.js)
- `samples/sample_usage.csv`: example of the CSV **format** (synthetic values, only for testing the upload)

---

## Text for the report: replaces section 3, "Pricing Assumptions and Sources"

> **3. Pricing Data and Sources (live)**
> The simulator does not use any fictional or hard-coded prices. At run time it calls the Azure Retail Prices API
> (https://prices.azure.com/api/retail/prices). This is Microsoft's official public price list. The simulator gets the
> pay-as-you-go, 1-year reserved, 3-year reserved and Spot hourly price of the selected VM size and region, plus Blob
> storage (per GB-month) and tiered internet egress (per GB). Spot interruption risk is taken from the AWS Spot Instance
> Advisor public dataset, using the equivalent instance type and region. Workload demand is real measured traffic from
> the Wikimedia Pageviews API for the last 30 complete days, or the user's own usage export (CSV). Every value is shown
> in the "Live price sources" tab with its API, meter ID and retrieval timestamp. For example, on 16 November 2026
> the Azure D2s v5 VM in East US cost $0.0960/h pay-as-you-go, $0.0592/h reserved for 1 year, $0.0379/h reserved for
> 3 years, and $0.0203/h on Spot, and the AWS m6i.large interruption band was >20% per month.
