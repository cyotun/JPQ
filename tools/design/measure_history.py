"""Measure Guandan history width for design review, using a local reference font.

No Android implementation or game-frequency claim is made by this calculation.
The font binary is intentionally absent from Git.
"""
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path

from PIL import ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT_PATH = Path(os.environ.get("JPQ_OVERLAY_FONT", str(ROOT / "research-private/dingtalk-official.ttf")))
REFERENCE = json.loads((ROOT / "docs/research/reference-display-defaults.json").read_text(encoding="utf-8"))
LABELS = ["大", "小", "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2"]
SAMPLES = ["Q·6·77722·AAA99·3", "9·4·小·66688·JJJ1010·大", "6·99944·K·J·10101088·7"]
BASE_RIGHT_DP = 320
END_PADDING_DP = 12
EM_SIZE = 1000


def ink_width_dp(value, size_sp):
    f = ImageFont.truetype(str(FONT_PATH), EM_SIZE)
    bounds = f.getbbox(value)
    return (max(f.getlength(value), bounds[2]) - min(0, bounds[0])) / EM_SIZE * size_sp


def build_report():
    digest = hashlib.sha256(FONT_PATH.read_bytes()).hexdigest()
    assert digest == REFERENCE["visualStyle"]["fontSha256"], "Use the verified official font."
    f = ImageFont.truetype(str(FONT_PATH), EM_SIZE)
    # Verify additive advances over every character pair used in this format.
    chars = sorted(set("".join(LABELS) + "·"))
    for left in chars:
        for right in chars:
            assert f.getlength(left + right) == f.getlength(left) + f.getlength(right)
    capacities = {label: 2 if label in ("大", "小") else 8 for label in LABELS}
    widths = {label: f.getlength(label) / EM_SIZE for label in LABELS}
    ranked = sorted(LABELS, key=widths.get, reverse=True)
    cards = []
    for label in ranked:
        cards.extend([label] * min(capacities[label], 27 - len(cards)))
    # Leading A has negative bearing. Include that ink overhang rather than
    # assuming advance alone gives the occupied width.
    cards.insert(0, cards.pop(cards.index("A")))
    maximum = "·".join(cards)
    adjustment = REFERENCE["twoDeckAdjustment"]
    base = REFERENCE["standardBase"]
    g, rec, win = base["grid"], base["record"], base["window"]
    size = rec["detailFontSizeSp"] * adjustment["recordDetailTextScale"]
    prefix = rec["labelWidthDp"] + rec["counterWidthDp"] + 2 * rec["spacingDp"]
    standard_width = 15 * g["cellWidthDp"] + 14 * g["cellSpacingDp"] + win["rightRecordWidthDp"] + 3
    old_inner = standard_width * adjustment["contentWidthScale"] - 3
    grid = old_inner * adjustment["gridSectionRatio"]
    old_right = old_inner - grid
    max_advance = f.getlength(maximum) / EM_SIZE * size
    max_ink = ink_width_dp(maximum, size)
    max_right = math.ceil(prefix + max_ink + END_PADDING_DP)
    body = grid + BASE_RIGHT_DP + 3
    sample_widths = [ink_width_dp(s, size) for s in SAMPLES]
    # An explanatory uniform-deal estimate, not an observed game distribution.
    mean_card_em = sum(widths[k] * v for k, v in capacities.items()) / 108
    mean_eight_hands = 27 * mean_card_em * size + 7 * f.getlength("·") / EM_SIZE * size + prefix + END_PADDING_DP
    return {
        "scope": "4 players, 2 decks, 27 physical cards per player after tribute; fontScale=1",
        "fontSha256": digest,
        "detailFontSizeSp": size,
        "separator": "·",
        "passAppendedToHistory": False,
        "maxCards": 27,
        "maxSeparators": 26,
        "capacityByRank": capacities,
        "advanceEmByRank": widths,
        "separatorAdvanceEm": f.getlength("·") / EM_SIZE,
        "maxCardComposition": dict(Counter(cards)),
        "maxHistoryExample": maximum,
        "maxHistoryAdvanceDp": max_advance,
        "maxHistoryInkDp": max_ink,
        "historyPrefixDp": prefix,
        "endPaddingDp": END_PADDING_DP,
        "defaultRightWidthDp": BASE_RIGHT_DP,
        "defaultDetailCapacityDp": BASE_RIGHT_DP - prefix - END_PADDING_DP,
        "maxRightWidthAtDefaultFontDp": max_right,
        "referenceGridWidthDp": grid,
        "referenceRightWidthDp": old_right,
        "defaultBodyWidthDp": body,
        "defaultBodyWithBothButtonsDp": body + 96,
        "defaultContentWidthScale": body / standard_width,
        "defaultGridSectionRatio": grid / (grid + BASE_RIGHT_DP),
        "rightWidthReductionDp": old_right - BASE_RIGHT_DP,
        "sampleHistories": SAMPLES,
        "sampleHistoryInkDp": sample_widths,
        "eightHandUniformDealEstimateRightDp": mean_eight_hands,
        "estimateIsEmpirical": False,
        "characterPairsChecked": len(chars) ** 2,
        "androidRuntimeVerified": False,
    }


if __name__ == "__main__":
    report = build_report()
    output = ROOT / "docs/design/history-width-analysis.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    adjustments = dict(REFERENCE["twoDeckAdjustment"])
    adjustments.update(
        contentWidthScale=report["defaultContentWidthScale"],
        gridSectionRatio=report["defaultGridSectionRatio"],
    )
    defaults = {
        "source": "User-approved JPQ design: moderate base width, expand only when history exceeds it",
        "referenceDefaults": "../research/reference-display-defaults.json",
        "calculation": "history-width-analysis.json",
        "twoDeckAdjustment": adjustments,
        "historyWidth": {
            "defaultRightSectionWidthDp": BASE_RIGHT_DP,
            "endPaddingDp": END_PADDING_DP,
            "autoExpand": True,
            "shrinkDuringRound": False,
            "resetToBaseOnNewRound": True,
            "measureConfirmedEventsOnly": True,
            "respectAvailableScreenWidth": True,
        },
        "androidRuntimeVerified": False,
    }
    (ROOT / "docs/design/display-defaults.json").write_text(json.dumps(defaults, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("maxHistoryInkDp", "defaultRightWidthDp", "maxRightWidthAtDefaultFontDp", "rightWidthReductionDp")}, indent=2))
