# ClosePaw Trading Strategy v1 — test protocol

Status: **research / paper validation only**. No autonomous real-money trading.

## 1. Goal

Create one repeatable decision template for ClosePaw that combines:
- market structure / price action;
- Smart Money Concepts (SMC/ICT-style concepts such as BOS, CHoCH, liquidity sweeps, order blocks, FVG);
- classical support/resistance and trend analysis;
- volume/liquidity filters;
- multi-timeframe confirmation;
- strict risk controls;
- visual verification against the chart shown in Phantom.

The strategy is not allowed to rely on one indicator or one school. A trade idea is valid only when several independent conditions agree.

## 2. Operating model

Analysis source may include major CEX market data (for example Binance/Bybit) plus public DEX data. Execution validation is performed against Phantom/Jupiter/Solana data.

CEX data is used for broad market structure and liquid OHLCV. DEX/Phantom is the execution reality check.

Before any trade recommendation, ClosePaw must compare the CEX-derived idea with the current DEX/Phantom price and reject the setup if execution conditions differ materially.

## 3. Allowed decisions

Only three outputs are valid:
- BUY
- SELL
- HOLD

If data is stale, incomplete, contradictory, the chart is ambiguous, or the score is below the threshold: **HOLD**.

## 4. Timeframes

Mandatory hierarchy:
- 4H: higher-timeframe trend / market regime;
- 1H: structure, key levels, major liquidity zones;
- 15m: setup confirmation;
- 5m: optional entry refinement only.

Never let a 5m signal override a conflicting 4H/1H structure.

## 5. Market regime first

Before looking for an entry, classify the market:

### Bull trend
- HH + HL structure;
- bullish BOS preferred;
- price generally holding above important support / demand zones.

### Bear trend
- LH + LL structure;
- bearish BOS preferred;
- price generally rejecting resistance / supply zones.

### Range / unclear
- repeated reversals between support and resistance;
- no clean HTF BOS;
- mixed structure.

If the regime is unclear, reduce confidence or HOLD.

## 6. Structure logic

ClosePaw must identify:
- swing highs;
- swing lows;
- HH / HL / LH / LL;
- BOS;
- CHoCH / market-structure shift.

Definitions for this strategy:
- bullish BOS: confirmed close above a meaningful prior swing high;
- bearish BOS: confirmed close below a meaningful prior swing low;
- bullish CHoCH: after bearish structure, price breaks a meaningful lower high;
- bearish CHoCH: after bullish structure, price breaks a meaningful higher low.

Do not call every small candle break a BOS/CHoCH.

## 7. Liquidity

Mark obvious liquidity pools:
- equal highs;
- equal lows;
- previous session / local swing highs;
- previous session / local swing lows;
- obvious breakout levels.

A liquidity sweep is useful confirmation when price trades beyond a visible liquidity level and then rejects / reclaims it.

A sweep alone is not an entry.

## 8. Order blocks

Use an order block only when it is connected to structure.

Bullish OB candidate:
- last meaningful bearish candle/zone before an impulsive bullish move;
- impulse should contribute to a structure break;
- stronger if unmitigated and aligned with HTF bias.

Bearish OB candidate:
- last meaningful bullish candle/zone before an impulsive bearish move;
- impulse should contribute to a structure break;
- stronger if unmitigated and aligned with HTF bias.

Do not label arbitrary consolidation candles as order blocks.

## 9. Fair Value Gap / imbalance

FVG is supporting evidence, not a standalone signal.

Preferred setup:
- HTF bias agrees;
- liquidity event or structural shift occurs;
- OB/FVG zone overlaps an important support/resistance or retracement area;
- price confirms reaction instead of blindly entering the zone.

## 10. Classical confirmation

Also evaluate:
- support and resistance;
- trendline/channel only when objectively visible;
- breakout vs failed breakout;
- rejection candles / engulfing reaction;
- volume expansion or contraction;
- volatility context.

Classical levels remain valid even if they do not receive an SMC label.

## 11. Multi-timeframe decision sequence

Use this exact order:

1. Determine 4H market regime.
2. Determine 1H structure and key support/resistance.
3. Mark nearby liquidity pools.
4. Mark valid OB/FVG zones if present.
5. Check whether price is at a meaningful location; do not chase in the middle of nowhere.
6. On 15m, look for confirmation: sweep, rejection, BOS/CHoCH, strong reaction.
7. Use 5m only to refine entry after 15m confirmation.
8. Check volume/liquidity and execution conditions.
9. Calculate invalidation, stop, target and R:R.
10. Score the setup.
11. Output BUY / SELL / HOLD.

## 12. Long setup template

A BUY is allowed only when most of the following agree:
- 4H bullish or neutral-with-clear-bullish-reversal context;
- 1H bullish structure or confirmed bullish shift;
- price is near support / discount / demand area, not extended far above it;
- sell-side liquidity sweep is a plus;
- bullish OB/FVG confluence is a plus;
- 15m bullish confirmation is present;
- volume does not contradict the move;
- execution price is still near the planned entry;
- minimum acceptable reward/risk is met.

Do not BUY merely because price has already risen strongly.

## 13. Sell setup template

A SELL is allowed only when most of the following agree:
- 4H bearish or neutral-with-clear-bearish-reversal context;
- 1H bearish structure or confirmed bearish shift;
- price is near resistance / premium / supply area, not extended far below it;
- buy-side liquidity sweep is a plus;
- bearish OB/FVG confluence is a plus;
- 15m bearish confirmation is present;
- volume does not contradict the move;
- execution price is still near the planned entry;
- minimum acceptable reward/risk is met.

For spot-only Phantom testing, SELL means reduce/exit an owned asset into USDC. It does not imply shorting unless a separate supported derivative venue is explicitly introduced later.

## 14. Scoring model

Score each candidate from 0 to 100.

Suggested weights:
- HTF trend / regime alignment: 20
- 1H structure / BOS / CHoCH quality: 20
- key support/resistance location: 15
- liquidity sweep/context: 10
- OB/FVG confluence: 10
- 15m confirmation: 10
- volume/liquidity quality: 5
- reward/risk quality: 5
- CEX vs DEX execution agreement: 5

Decision thresholds:
- 75–100: strong candidate; BUY/SELL may be issued if no hard veto applies;
- 65–74: conditional setup; usually wait for additional confirmation;
- below 65: HOLD.

Confidence is not the same as probability of profit. It is a measure of how completely the observed setup satisfies this template.

## 15. Hard veto rules

Always output HOLD when any of these apply:
- stale or contradictory core market data;
- wrong or uncertain token/mint/pool;
- chart cannot be read reliably;
- HTF structure is ambiguous and no strong reversal confirmation exists;
- price is too far from planned entry;
- liquidity is too low;
- price impact / slippage exceeds limits;
- expected R:R is too poor;
- stop/invalidation cannot be defined objectively;
- setup depends only on one indicator/pattern;
- the final Phantom execution screen materially disagrees with the analysis.

## 16. Initial risk parameters for testing

During validation:
- PAPER / OBSERVATION first;
- no autonomous final Swap/Confirm;
- base asset: USDC;
- test markets initially: SOL/USDC, JUP/USDC, RAY/USDC; BONK may be used as a stress-test high-volatility market;
- max slippage target: 0.5%;
- max price impact target: 1.0%;
- max CEX-vs-DEX price difference target: 0.5%;
- prefer planned R:R >= 1.5; >= 2.0 is stronger;
- if R:R < 1.2, HOLD unless the test explicitly studies that case.

These are test defaults, not proven optimal values. They must be revised from backtest/forward-test results.

## 17. Phantom visual verification protocol

When we test chart recognition in Phantom, ClosePaw must separate **observation** from **interpretation**.

First report what is actually visible:
- token pair;
- current displayed price;
- selected timeframe;
- visible local high(s) and low(s);
- obvious trend direction;
- visible support/resistance zones;
- any clear breakout/rejection;
- whether volume is visible at all.

Then report interpretation:
- market regime;
- structure;
- BOS/CHoCH candidate;
- liquidity zones/sweeps;
- OB/FVG only if visually supportable;
- BUY/SELL/HOLD.

Never invent an indicator, candle value, volume value, OB, FVG, BOS or CHoCH that is not actually visible or available from market data.

## 18. Output format

For every analysis, output exactly:

PAIR:
NETWORK:
ANALYSIS_TIMESTAMP:
DATA_SOURCES:
TIMEFRAMES_USED:

MARKET_REGIME:
HTF_STRUCTURE:
KEY_SUPPORT:
KEY_RESISTANCE:
LIQUIDITY_CONTEXT:
ORDER_BLOCKS:
FVG_IMBALANCE:
VOLUME_LIQUIDITY:

ACTION: BUY / SELL / HOLD
SETUP_SCORE: 0-100
CONFIDENCE: 0-100
ENTRY:
INVALIDATION:
STOP:
TAKE_PROFIT_1:
TAKE_PROFIT_2:
RISK_REWARD:
TIME_HORIZON:

BULL_CASE:
BEAR_CASE:
WHY_NOT_THE_OPPOSITE_ACTION:
HARD_VETO_TRIGGERED: YES / NO
VETO_REASON:

PHANTOM_CHECK_REQUIRED:
- exact token/mint
- displayed execution price
- slippage
- price impact
- expected received amount
- sufficient SOL fee reserve

FINAL_STATUS: READY_FOR_REVIEW / WAIT / HOLD

## 19. Forward-test protocol

For every forecast, store:
- timestamp;
- pair;
- ACTION;
- setup score;
- confidence;
- current price;
- entry;
- stop;
- TP1/TP2;
- reasons;
- screenshot/chart state if available.

After the stated horizon, evaluate:
- whether entry was reached;
- order of events after entry;
- whether stop or TP was hit first;
- maximum favorable excursion (MFE);
- maximum adverse excursion (MAE);
- whether HOLD avoided a bad trade or missed a strong move;
- calibration of confidence vs actual results.

Do not judge a forecast only from the final price. Path/order of events matters.

## 20. Strategy-development rule

This document is a hypothesis to test, not a claim of profitability.

After enough observations, keep only rules that improve measured results. Remove rules that add complexity without measurable benefit. Do not change rules retroactively to make old forecasts look correct.
