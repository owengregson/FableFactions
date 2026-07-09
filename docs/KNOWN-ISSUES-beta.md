# Known issues — v1.0.0-beta.2

The wave-5 adversarial review surfaced 46 confirmed findings. Every **critical** (item
duplication, money duplication, state corruption, dead protection) and every **high**
(unfair advantage, data loss) is fixed in this beta. The items below are the remaining
**medium/low** deferrals — tracked, bounded, and none of them duplicate items or money or
corrupt state. Each names the fix for a follow-up.

## Economy
- **Deposit/power-buy crash window (residual of #3/#18).** A rejected lane (busy/shutdown)
  now refunds the wallet, and the reducer refunds its own rejections. The only remaining
  gap is a hard crash in the sub-millisecond window *between* the Vault debit and the
  intent reducing — the bounded, conservative window AM-7 explicitly documents (never a
  duplicate; at worst a refund of money a crash already moved). Follow-up: the full
  escrow-first deposit saga (`OpenEscrow(DEPOSIT)` journaled before the Vault call).
- **Bank tax anchor (#25).** The periodic bank-tax sweep has no persisted anchor, so a
  server restarted more often than `tax.interval-hours` can skip a sweep. Follow-up:
  persist the last-sweep timestamp and run a catch-up sweep at boot when overdue.

## Durability (all non-corrupting)
- **CRITICAL-tier confirmation ack (#24/#32).** The fsync now runs off the writer thread
  and the durable-seq ack **seam** is wired, but the consumer that *holds* a CRITICAL
  user confirmation until its fsync completes is not landed — CRITICAL confirmations
  currently release on STATE timing. No data is lost (the journal is durable); only the
  "shown after fsync" guarantee is not yet end-to-end.
- **Unloaded-world claims (#23).** Claims for a world not yet loaded at boot are now
  **retained and warned** (previously silently dropped), but they activate only after a
  full `WorldLoadEvent` re-load path, which is not landed — such a world's protection is
  inactive until the world loads. Follow-up: re-run the board load for the world on
  `WorldLoadEvent`.

## Threading / observability (low)
- **Off-thread player resolution (#41).** A couple of feedback paths resolve a Bukkit
  player off the ideal region thread. Follow-up: route through `Scheduling.runOn`.
- **AM-8 freeze tripwire (#46).** The debug-only snapshot-array-mutation tripwire
  (`-Dfable.debug.freeze`, CI-on) is not implemented. Zero runtime impact; a CI-only
  safety net for a class of bug the COW discipline already prevents.
- **Paged-disband drift termination.** The disband/merge final-page loop keys on the
  `landCount` aggregate; the AM-4 ReconcileSweep now self-heals that aggregate on a 30-min
  cadence, so a drift-induced re-spin is bounded. Follow-up: key the loop on the
  per-page removed-count for immediate robustness.

## Not-yet-wired features (render cleanly, no crash)
- **`/f bank history` and `/f power history`** render their header + an empty list until
  the LEDGER history-query seam is wired (the rows are journaled; the read path is
  pending). Their message keys are authored, so they read correctly rather than as raw text.
- **Public API surface.** `FableFactionsApi` is defined, but the `ServicesManager`
  registration + event bridge are not landed — third-party plugins can't yet resolve the
  service. Follow-up: register on enable + wire `ApiEventBridge`.
- **Operator-edited `gui.yml`.** `/f gui` works from the shipped menu definitions; an
  operator's edits to their data-folder `gui.yml` are not yet honored (boot passes the
  bundled catalog). Follow-up: pass `MenuCatalog.parse(sources.gui())` in `FeatureReconciler`.
- **Translations.** English only for this beta; the locale waterfall + `/f language` seam
  are in place, and additional bundles will be added once they can be kept in key parity.
