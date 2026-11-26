# Karukan Android 実装仕様・キーバインド詳細 (Implementation Details & Shortcuts)

本ドキュメントでは、Android 向け Karukan IME フロントエンドモジュール (`karukan-im/android`) における、キーボードショートカット（物理キー・ファンクションキー・Ctrl/Shiftコンビネーション）、他プラットフォームとのキーバインド比較、および手書きストローク認識エンジンの実装詳細について記述します。

（※アーキテクチャ設計については [DESIGN.md](DESIGN.md)、機能仕様および JNI インターフェースについては [SPECIFICATION.md](SPECIFICATION.md)、エンドユーザー向け操作手順については [MANUAL.md](MANUAL.md) を参照してください。）

---

## 1. ショートカットキー・物理キーボード対応仕様 (Keyboard Shortcuts)

他フロントエンド（fcitx5 / macOS）との互換性を持つ物理キーボードおよびソフトキーボードのショートカット仕様です。
設定画面の「キーショートカットの設定」から、ユーザーがキーの組み合わせとアクションを自由に変更・定義・未割り当て化できます。（※Android標準の編集ショートカット Ctrl+A, C, V, X, Z は安全のため予約済みとして固定されています）

### 1.1. ファンクションキー変換 (Function Keys)
| キー | 機能 | 動作・処理 |
|---|---|---|
| **F6** | ひらがな変換 | 入力中の文字列をひらがなへ変換（未確定表示） |
| **F7** | 全角カタカナ変換 | 入力中の文字列を全角カタカナへ変換（未確定表示） |
| **F8** | 半角カタカナ変換 | 入力中の文字列を半角カタカナへ変換（未確定表示） |
| **F9** | 全角英数変換 | 入力中の文字列を全角英数へ変換（連続押下で小文字/大文字/頭大文字トグル、未確定表示） |
| **F10** | 半角英数変換 | 入力中の文字列を半角英数へ変換（連続押下で小文字/大文字/頭大文字トグル、未確定表示） |

### 1.2. Ctrl キーショートカット (Ctrl Shortcuts)
| ショートカット | 機能 | 動作・制御条件 | 仕様区分 |
|---|---|---|---|
| **Ctrl + A** | 全選択 | 編集領域のテキスト全選択 (`selectAllEditingArea` -> `InputConnection.selectAll`) | 共通仕様 |
| **Ctrl + B** | カーソル左移動 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準の太字設定等へ委譲） | 共通仕様 |
| **Ctrl + C** | コピー | 編集領域のテキストコピー (`copyEditingArea` -> `InputConnection.copy`) | 共通仕様 |
| **Ctrl + D** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + E** | 😃絵文字パレットオープン | 絵文字・記号パレットを開く (`toggle_emoji_palette`) | 共通仕様 |
| **Ctrl + F** | カーソル右移動 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準検索等へ委譲） | 共通仕様 |
| **Ctrl + G** | キャンセル | 変換・候補選択のキャンセル (`onEscape`) | 共通仕様 |
| **Ctrl + H** | 文字削除 | 前の1文字を削除 (`onBackspace`) | 共通仕様 |
| **Ctrl + I** | 🤖AI 候補ビューオープン | AI 候補ビューを直接開く (`filterByAiSource`) | 共通仕様 |
| **Ctrl + J** | Chunk区切り・固定 | 未確定表示のまま Chunk を区切り固定 (`fixChunkPreedit`、確定・改行なし) | 共通仕様 |
| **Ctrl + K** | カタカナモード切替 | カタカナ入力モードへのトグル切替 (`commitKatakanaOrToggleMode`) | 共通仕様 |
| **Ctrl + L** | ライブ変換トグル | リアルタイムライブ変換の有効/無効切替 (`onToggleLiveConversion`) | **Android独自** |
| **Ctrl + M** | 確定 | 入力内容を確定 (`onCommitKey`) | 共通仕様 |
| **Ctrl + N** | 次候補 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準の新規作成等へ移譲） | 共通仕様 |
| **Ctrl + O** | *未割当* | 常時 OS / アプリ標準機能へ委ねる | - |
| **Ctrl + P** | 前候補 | 入力中・候補選択中のみ動作 (`onPreviousCandidate`)（未入力時は OS/アプリ標準印刷等へ移譲） | 共通仕様 |
| **Ctrl + Q** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + R** | 🔄記号変換ビューオープン | 記号変換ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.SYMBOL`) | 共通仕様 |
| **Ctrl + S** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + T** | 📝学習ソースビューオープン | 学習ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.LEARNING`) | 共通仕様 |
| **Ctrl + U** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + V** | 貼り付け | クリップボードから貼り付け (`pasteEditingArea` -> `InputConnection.paste`) | 共通仕様 |
| **Ctrl + W** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + X** | 切り取り | 編集領域のテキスト切り取り (`cutEditingArea` -> `InputConnection.cut`) | 共通仕様 |
| **Ctrl + Y** | *未割当* | 常時 OS / アプリ標準機能へ委ねる（IME側では消費しない） | - |
| **Ctrl + Z** | 元に戻す (Undo) | 直前の編集操作を元に戻す (`undoEditingArea` -> `InputConnection.undo`) | 共通仕様 |
| **Ctrl + 1 〜 9** | 候補番号選択・確定 | 表示中の 1〜9 番目の候補を直接選択・確定 (`SelectCandidateNumber`) | 共通仕様 |
| **Ctrl + Backspace / Delete** | 候補学習削除 | 選択中の変換候補を学習辞書から削除 (`onDeleteSelectedCandidate`) | 共通仕様 |
| **Ctrl + Space** | モード切替 | 日本語(ひらがな) / 英語(英数) モードのトグル切替 (`onToggleLanguageMode`) | 共通仕様 |

### 1.3. Shift キー組み合わせ操作 (Shift Combinations)
| ショートカット | 機能 | 動作・処理 |
|---|---|---|
| **Shift + ← / → / ↑ / ↓** | テキスト範囲選択 | 選択範囲を変更する KeyEvent を送信 (`sendKeyEvent` with `META_SHIFT_ON`) |
| **Shift + Tab** | 逆方向移動 / 前候補 | 候補選択時は前候補選択、非入力時は逆方向フォーカス移動 |
| **Shift + Space** | 全角スペース / 前候補 | 入力中/変換時: 前候補選択 (`onPreviousCandidate`) / 非入力時: 全角スペースを入力 (`onFullWidthSpace`) |

### 1.4. JIS 109キーボード物理キー仕様 (JIS 109 Physical Keyboard Keys)
JIS 109キーボード特有の物理キー（スキャンコード対応）の動作仕様です。

| キー | 機能 | 動作・処理 |
|---|---|---|
| **変換** | 日本語モード切替 / 変換・次候補 | 未入力時は日本語（ひらがな）モードへ切り替え (`InputMode.HIRAGANA`)、入力中・候補選択中は次候補選択（変換） (`onSpaceKey`) |
| **無変換** | 英語モード切替 | 未確定文字のコミット後、英語（英数）モードへ切り替え (`EisuMode`) |
| **半角/全角** | 言語モード切替 | 日本語（ひらがな）モードと英語（英数）モードのトグル切替 (`ToggleLanguageMode`) |
| **カタカナ/ひらがな** | かなモード切替 | 入力中はひらがな変換、非入力時はひらがなモードへ切替 (`KanaMode`) |
| **￥ / ろ / 記号キー** | JIS記号入力 | JIS配列の刻印（`￥`, `｜`, `\`, `_`, `^`, `@` 等）に合わせた文字入力 (`jisCharacterMap`) |

---

## 2. 他の Karukan フロントエンドとの動作仕様比較 (Cross-Platform Frontend Comparison)

`karukan-im` リポジトリ内の各フロントエンド（`karukan-im/fcitx5`, `karukan-im/macos`, `karukan-im/windows`）と `karukan-im/android` のキーバインド・アクション比較です。

| ショートカット | `karukan-im / fcitx5` | `karukan-im / macos` | `karukan-im / windows` | `karukan-im / android` | 補足・仕様区分 |
|---|---|---|---|---|---|
| **Ctrl + A** | 行頭へ移動 | 行頭へ移動 | 全選択 | **全選択** | モバイル向け割り当て |
| **Ctrl + B** | カーソル左移動 | カーソル左移動 | カーソル左移動 | **カーソル左移動** | Emacsバインド共通 |
| **Ctrl + C** | OS標準 | OS標準 | OS標準 | **コピー** | モバイル標準コピー |
| **Ctrl + E** | - | - | - | **絵文字パレット** | Android独自 |
| **Ctrl + F** | カーソル右移動 | カーソル右移動 | カーソル右移動 | **カーソル右移動** | Emacsバインド共通 |
| **Ctrl + G** | キャンセル | キャンセル | キャンセル | **キャンセル** | 共通キャンセル |
| **Ctrl + H** | 1文字削除 | 1文字削除 | 1文字削除 | **1文字削除** | Emacsバインド共通 |
| **Ctrl + I** | AI候補 | AI候補 | AI候補 | **AI候補** | Karukan共通機能 |
| **Ctrl + J** | Chunk固定 | Chunk固定 | Chunk固定 | **Chunk固定** | Karukan共通機能 |
| **Ctrl + K** | カタカナ切替 | カタカナ切替 | カタカナ切替 | **カタカナ切替** | Karukan共通機能 |
| **Ctrl + L** | - | - | - | **ライブ変換切替** | Android独自 |
| **Ctrl + M** | 確定 | 確定 | 確定 | **確定** | 共通確定 |
| **Ctrl + N** | 次候補 | 次候補 | 次候補 | **次候補** | 共通次候補 |
| **Ctrl + P** | 前候補 | 前候補 | 前候補 | **前候補** | 共通前候補 |
| **Ctrl + R** | 記号・日付 | 記号・日付 | 記号・日付 | **記号・日付** | Karukan共通機能 |
| **Ctrl + T** | 学習ソース | 学習ソース | 学習ソース | **学習ソース** | Karukan共通機能 |
| **Ctrl + V** | OS標準 | OS標準 | OS標準 | **貼り付け** | モバイル標準貼り付け |
| **Ctrl + X** | OS標準 | OS標準 | OS標準 | **切り取り** | モバイル標準切り取り |
| **Ctrl + Z** | OS標準 | OS標準 | OS標準 | **Undo** | モバイル標準Undo |
| **Ctrl + 1〜9** | 候補番号選択 | 候補番号選択 | 候補番号選択 | **候補番号選択** | Karukan共通機能 |
| **Ctrl + Space** | モード切替 | モード切替 | モード切替 | **モード切替** | モード切替 |

---

## 3. 手書きストローク認識エンジン仕様 (Graffiti Stroke Recognition Engine)

Palm OS Graffiti (unistroke) 手書き入力エリア (`GraffitiAreaView`) におけるジェスチャー認識は、固定的な単純テンプレートマッチングではなく、**機械学習型・高次元特徴量ベースの重み付き k-NN 分類器**を採用しています。

### 主な技術仕様
1. **アセットテンプレートのロード (`graffiti_templates.json`)**:
   - アセット内の `graffiti_templates.json` からベーステンプレートおよび各回転角（0°, 90°, 180°, 270°）の回転バリエーション仕様をパースし、メモリ上にトレーニングサンプル群（ALPHA, NUMERIC, PUNCTUATION）としてインデックス化。
2. **高次元特徴量ベクトルの抽出 (Feature Extraction & Vectorization)**:
   - 入力・テンプレートストロークの軌跡全体を 32等分（`DEFAULT_SAMPLING_COUNT = 32`）にリサンプルし、正方形サイズにスケーリングおよび原点中心へ平行移動した正規化ポイント（64次元の $(x, y)$ 座標）を取得。
   - さらに、グローバル形状メトリクス（パス長 `pathLength`、始点・終点間のユークリッド距離 `startEndDistance`、水平・垂直方向の総移動量 `totalDx`, `totalDy`）を統合した高次元特徴量ベクトル (`FloatArray`) を生成し、一筆書きの「途中経過（カーブ・形状・方向）」のすべてを厳密に評価。
3. **重み付き k-Nearest Neighbors (k-NN, $k=5$) 分類**:
   - 入力ストロークと各トレーニングサンプルの特徴量ベクトル間ユークリッド距離を計算し、上位 $k=5$ 個の近傍候補を選出（デバッグ用に上位5候補を `logI` 出力）。
   - 距離の近さに応じた逆距離重み付け（Inverse Distance Weighting）により各文字・機能クラスのスコアを算出して最適候補を判定。
