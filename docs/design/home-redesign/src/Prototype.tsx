import { useState, type CSSProperties, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { CheckIcon, ChevronRightIcon, Cross1Icon, GearIcon, HeartFilledIcon, MagnifyingGlassIcon, Pencil1Icon, PlusIcon } from "@radix-ui/react-icons";
import { BottomSheet, KeyboardInput, MobileScroll, useKeyboard, useKeyboardInsets, useMobileDevice } from "./mobile";

const RANKS = ["大", "小", "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2"];
const INITIAL_COUNTS = RANKS.map((_, index) => index < 2 ? 2 : 8);
// In this preview, the 8 cell excludes the two heart-8 wildcards in their own cell.
const SAMPLE_LEVEL = "8";
const SAMPLE_HEART_COUNT = 2;
const SAMPLE_COUNTS = [1, 1, 6, 4, 6, 5, 3, 0, 4, 1, 0, 0, 4, 4, 4];
const PLAY_TYPES = ["斗地主", "跑得快", "双扣", "掼蛋", "510K", "干瞪眼", "锄大地", "红十", "A3纸牌", "自定义"];
const GAMES = [
  { id: "tencent-daguandan", name: "大掼蛋", publisher: "腾讯", available: true },
  { id: "tencent-doudizhu", name: "欢乐斗地主", publisher: "腾讯", available: false },
  { id: "tencent-guandan", name: "欢乐掼蛋", publisher: "腾讯", available: false },
  { id: "jj-doudizhu", name: "JJ斗地主", publisher: "JJ", available: false },
];
type Sheet = "games" | "preview" | "diagnostics" | "updates" | null;
type RecordMode = "hidden" | "counts" | "full";

function SectionHeading({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return <div className="section-heading"><h2>{children}</h2>{aside ? <span>{aside}</span> : null}</div>;
}
function RankCounts({ preview = false }: { preview?: boolean }) {
  const counts = preview ? SAMPLE_COUNTS : INITIAL_COUNTS;
  return <div className={preview ? "rank-counts preview-counts" : "rank-counts"} aria-label={preview ? "剩余牌示例" : "初始总牌数"}>
    {preview ? <div className="rank-cell heart-cell" aria-label={`红桃 ${SAMPLE_LEVEL} 逢人配 ${SAMPLE_HEART_COUNT} 张`}><span className="rank-label"><HeartFilledIcon aria-hidden="true" /></span><span className="rank-value">{SAMPLE_HEART_COUNT}</span></div> : null}
    {RANKS.map((rank, index) => <div className="rank-cell" key={rank} aria-label={`${preview && rank === SAMPLE_LEVEL ? "非红桃级牌 " : ""}${rank === "大" ? "大王" : rank === "小" ? "小王" : rank} ${counts[index]} 张`}>
      <span className={`rank-label ${rank === "10" ? "double-digit" : ""} ${preview && rank === SAMPLE_LEVEL ? "level-label" : ""}`}>{rank}{preview && rank === SAMPLE_LEVEL ? <span className="level-badge" aria-label="当前级牌" /> : null}</span><span className={`rank-value ${preview ? counts[index] === 0 ? "zero" : counts[index] >= (index < 2 ? 2 : 4) ? "bomb" : "" : ""}`}>{counts[index]}</span>
    </div>)}
  </div>;
}
function FloatingSample({ mode, showFlush = true }: { mode: RecordMode; showFlush?: boolean }) {
  return <div className={`floating-sample ${mode !== "hidden" ? "with-records" : ""}`} aria-label="悬浮记牌条示例">
    <RankCounts preview />
    {showFlush ? <span className="sample-flush" aria-label="同花顺仍有可能"><img src="/assets/straight-flush.svg" alt="" draggable={false} /></span> : null}
    {mode !== "hidden" ? <div className={`sample-records ${mode === "counts" ? "counts-only" : ""}`}>
      {[{ seat: "上", count: 13, text: "Q · 6 · 77722" }, { seat: "对", count: 14, text: "9 · 4 · 小" }, { seat: "下", count: 14, text: "6 · 99944" }].map(row =>
        <div className="sample-record" key={row.seat}><span>{row.seat}</span><strong>{row.count}</strong>{mode === "full" ? <em>{row.text}</em> : null}</div>)}
    </div> : null}
  </div>;
}
function Switch({ label, checked, onChange, description }: { label: string; checked: boolean; onChange: () => void; description?: string }) {
  return <div className="setting-row"><div><span>{label}</span>{description ? <small>{description}</small> : null}</div>
    <button className="setting-switch" type="button" role="switch" aria-label={label} aria-checked={checked} data-on={checked} onClick={onChange}><span className="switch-thumb" /></button>
  </div>;
}

// App-specific screens only. Device, status bar, keyboard and picker stay in the template.
export default function Prototype() {
  const { device } = useMobileDevice();
  const keyboard = useKeyboard();
  const { bottomInset } = useKeyboardInsets();
  const [tab, setTab] = useState<"count" | "settings">("count");
  const [game, setGame] = useState<string | null>("tencent-daguandan");
  const [sheet, setSheet] = useState<Sheet>(null);
  const [query, setQuery] = useState("");
  const [running, setRunning] = useState(false);
  const [recordMode, setRecordMode] = useState<RecordMode>("full");
  const [showReset, setShowReset] = useState(true);
  const [doubleHide, setDoubleHide] = useState(false);
  const [showFlush, setShowFlush] = useState(true);
  const [showGradeTip, setShowGradeTip] = useState(true);
  const selectedGame = GAMES.find(item => item.id === game);
  const filteredGames = GAMES.filter(item => `${item.name}${item.publisher}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const style = { "--app-top": `${device.geometry.safeArea.top}px`, "--app-bottom": `${bottomInset}px` } as CSSProperties;
  const openSheet = (next: Sheet) => { keyboard.hide(); setSheet(next); };
  const closeSheet = () => { keyboard.hide(); setSheet(null); };
  const changeTab = (next: "count" | "settings") => { keyboard.hide(); setTab(next); };

  return <div className="jpq-prototype" style={style} data-htmlprd-page-id={tab === "count" ? "jpq.count.home" : "jpq.settings.home"}>
    <header className="jpq-header"><h1>{tab === "count" ? "记牌器" : "设置"}</h1><span className={running ? "header-state active" : "header-state"} aria-live="polite">{running ? "记牌中" : "本地记牌"}</span></header>
    {tab === "count" ? <MobileScroll className="jpq-body jpq-home-body"><main className="home-content" data-testid="count-home">
      <section className="game-section" aria-labelledby="game-heading">
        <SectionHeading aside="1 款已适配"><span id="game-heading">1、选择游戏平台</span></SectionHeading>
        <div className="game-selection"><button type="button" className="add-game" aria-label="添加游戏平台" data-htmlprd-id="jpq.count.select-game" disabled={running} onClick={() => openSheet("games")}><PlusIcon /></button>
          {selectedGame ? <div className="selected-game"><button type="button" className="selected-game-name" aria-label="切换游戏平台" disabled={running} onClick={() => openSheet("games")}>{selectedGame.name}<span>（{selectedGame.publisher}）</span></button><button type="button" className="remove-game" aria-label="移除大掼蛋" disabled={running} onClick={() => { setGame(null); openSheet("games"); }}><Cross1Icon /></button></div> : <span className="selection-placeholder">请添加游戏平台</span>}
        </div>
      </section>
      <section aria-labelledby="play-heading">
        <SectionHeading aside="更多玩法待适配"><span id="play-heading">2、选择玩法</span></SectionHeading>
        <div className="option-grid play-grid" role="group" aria-label="选择玩法">{PLAY_TYPES.map(play => <button type="button" className={`option-chip ${play === "掼蛋" ? "selected" : ""}`} key={play} disabled={play !== "掼蛋" || running} aria-pressed={play === "掼蛋"}>{play}</button>)}</div>
        <div className="variant-row"><span>细分玩法</span><div className="variant-options" role="group" aria-label="细分玩法"><button className="option-chip selected" type="button" aria-pressed="true" disabled={running}>默认</button><button className="option-chip" type="button" disabled>团团转</button></div></div>
      </section>
      <section aria-labelledby="players-heading"><SectionHeading><span id="players-heading">3、选择玩家人数</span></SectionHeading><div className="option-grid players-grid" role="group" aria-label="玩家人数">{[2, 3, 4, 5, 6].map(number => <button type="button" className={`option-chip ${number === 4 ? "selected" : ""}`} key={number} disabled={number !== 4 || running} aria-pressed={number === 4}>{number}人</button>)}</div></section>
      <section aria-labelledby="decks-heading"><SectionHeading aside="当前适配 4 人 · 2 副"><span id="decks-heading">4、几副牌</span></SectionHeading><div className="option-grid decks-grid" role="group" aria-label="牌副数">{[1, 2, 3, 4, 5, 6].map(number => <button type="button" className={`option-chip ${number === 2 ? "selected" : ""}`} key={number} disabled={number !== 2 || running} aria-pressed={number === 2}>{number}副</button>)}</div><div className="initial-deck"><RankCounts /><p>初始总牌数 108 张</p></div></section>
      <section className="sample-section" aria-label="悬浮记牌条示例"><div className="sample-heading"><span>悬浮记牌条</span><button type="button" onClick={() => openSheet("preview")} aria-label="查看悬浮窗预览">预览<ChevronRightIcon /></button></div><FloatingSample mode={recordMode} showFlush={showFlush} /></section>
    </main></MobileScroll> : <MobileScroll className="jpq-body jpq-settings-body"><main className="settings-content" data-testid="settings-home">
      <div className="settings-section-title">悬浮记牌器</div><section className="settings-panel">
        <div className="record-mode-row"><span>三家出牌显示</span><div className="record-mode-options" role="group" aria-label="三家出牌显示">{([{ value: "hidden", label: "关闭" }, { value: "counts", label: "仅数量" }, { value: "full", label: "数量和明细" }] as const).map(item => <button key={item.value} className={`option-chip ${recordMode === item.value ? "selected" : ""}`} type="button" aria-pressed={recordMode === item.value} onClick={() => setRecordMode(item.value)}>{item.label}</button>)}</div></div>
        <Switch label="显示重置按钮" checked={showReset} onChange={() => setShowReset(!showReset)} /><Switch label="双击记牌器隐藏" description="隐藏后显示可拖动的「显示」按钮" checked={doubleHide} onChange={() => setDoubleHide(!doubleHide)} /><Switch label="显示同花顺标识" checked={showFlush} onChange={() => setShowFlush(!showFlush)} /><Switch label="显示级牌遮挡提示" checked={showGradeTip} onChange={() => setShowGradeTip(!showGradeTip)} />
      </section><div className="settings-section-title">应用设置</div><section className="settings-panel app-settings-panel"><div className="setting-row permission-summary"><div><span>权限</span><small>悬浮窗、屏幕共享 · 示例均已就绪</small></div><CheckIcon /></div><button className="settings-link" type="button" onClick={() => openSheet("updates")}>应用更新<ChevronRightIcon /></button><button className="settings-link" type="button" onClick={() => openSheet("diagnostics")}>牌局与诊断<ChevronRightIcon /></button></section><p className="settings-footnote">设置在记牌页的预览中同步生效。<br />原型不读取手机画面，也不执行应用更新。</p>
    </main></MobileScroll>}
    <footer className={`jpq-footer ${tab === "settings" ? "settings-footer" : ""}`}>
      {tab === "count" ? <div className="start-area"><p className="start-caption" aria-live="polite">{running ? "进入游戏房间后，会自动开始记牌" : "先开启记牌，再进入游戏房间"}</p><button className="start-button" type="button" disabled={!game} onClick={() => setRunning(!running)} data-testid="start-stop" data-htmlprd-id="jpq.count.start-stop">{running ? "停止记牌" : "开始记牌"}</button></div> : null}
      <nav className="bottom-tabs" aria-label="主要导航"><button type="button" className={tab === "count" ? "selected" : ""} aria-current={tab === "count" ? "page" : undefined} onClick={() => changeTab("count")}><Pencil1Icon /><span>记牌</span></button><button type="button" className={tab === "settings" ? "selected" : ""} aria-current={tab === "settings" ? "page" : undefined} onClick={() => changeTab("settings")}><GearIcon /><span>设置</span></button></nav>
    </footer>
    <BottomSheet open={sheet === "games"} onOpenChange={open => { if (!open) closeSheet(); }} title="选择游戏平台" description="当前仅大掼蛋（腾讯）可用，其余为扩展位置。" snap={0.66}>
      <div className="game-search"><MagnifyingGlassIcon /><KeyboardInput aria-label="搜索游戏平台" value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索游戏" /></div><div className="game-list">{filteredGames.map(item => <button className="game-list-item" type="button" key={item.id} disabled={!item.available} onClick={() => { setGame(item.id); closeSheet(); }}><span><strong>{item.name}</strong><small>{item.publisher}</small></span>{item.available ? game === item.id ? <CheckIcon /> : <span className="game-available">已适配</span> : <span className="game-pending">待适配</span>}</button>)}{!filteredGames.length ? <div className="search-empty">没有找到匹配的游戏</div> : null}</div><button className="sheet-close-button" type="button" onClick={closeSheet}>完成</button>
    </BottomSheet>
    <BottomSheet open={sheet === "preview"} onOpenChange={open => { if (!open) closeSheet(); }} title="悬浮记牌条预览" description="这里展示样式，数字与出牌记录均为示例。" snap={0.48}>
      <div className="expanded-preview"><FloatingSample mode={recordMode} showFlush={showFlush} /></div><div className="preview-summary"><span>{showReset ? "显示重置按钮" : "不显示重置按钮"}</span><span>{doubleHide ? "双击隐藏" : "按钮隐藏"}</span><span>{showFlush ? "同花顺标识开启" : "同花顺标识关闭"}</span></div><button className="sheet-close-button" type="button" onClick={closeSheet}>返回记牌页</button>
    </BottomSheet>
    <BottomSheet open={sheet === "updates"} onOpenChange={open => { if (!open) closeSheet(); }} title="应用更新" description="保留手动检查与自动更新入口。" snap={0.40}>
      <div className="diagnostics-list"><span>每日自动检查<small>对局期间不提示安装</small></span><span>检查更新<small>仅样式预览，不连接服务器</small></span></div><button className="sheet-close-button" type="button" onClick={closeSheet}>返回设置</button>
    </BottomSheet>
    <BottomSheet open={sheet === "diagnostics"} onOpenChange={open => { if (!open) closeSheet(); }} title="牌局与诊断" description="调试功能集中在这里，首页保持简洁。" snap={0.44}>
      <div className="diagnostics-list"><span>最近牌局<small>原型暂无牌局</small></span><span>导出牌局诊断<small>真实应用中导出</small></span><span>清理牌局和诊断<small>真实应用中清理</small></span></div><button className="sheet-close-button" type="button" onClick={closeSheet}>返回设置</button>
    </BottomSheet>
    {createPortal(<aside className="prototype-note"><strong>记牌器 · 首页设计确认</strong><span>交互原型，开始／停止与悬浮数字均为示例。</span><span>不会录屏，不会进入游戏，不影响手机牌局。</span></aside>, document.body)}
  </div>;
}
