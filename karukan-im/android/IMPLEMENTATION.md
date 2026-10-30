# Karukan Android 実装仕様書 (Implementation Guide)

Karukan IME Android 移植版 (`karukan-im/android`) のモジュール構造、JNI 連携、キーボード処理、辞書管理、およびライフサイクル制御などの実装情報です。

---

## 1. アプリ・コンポーネント構成

- **入力サービス・表示ビュー**:
  - `KarukanInputMethodService.kt`: IME ライフサイクル管理（ウィンドウ非表示、入力ビュー終了・開始時のキーボード状態リセット）、キーイベント処理、ライブ変換・確定制御
  - `KarukanInputView.kt`: キーボード表示、候補インライン表示、フローティング候補表示の親ビュー。絵文字・記号パレットおよび候補展開の管理 (`resetKeyboardState()`, `hideEmojiPalette()`)
  - `CandidateFlowLayout.kt`: 候補一覧のフローレイアウト管理
- **エンジンブリッジ・設定管理**:
  - `KarukanEngine.kt`: JNI ブリッジ、GGUF モデル切り替え、Native 辞書・設定・推論制御 (`Companion.calculateOptimalNThreads()` 等)
  - `PreferencesManager.kt`: `SharedPreferences` の読み込みと設定 (`KarukanSettings`) 生成
  - `KarukanSettings.kt`: 設定データモデル
- **キーボードUI・マッパー**:
  - `KeyboardCommon.kt`: キーボード共通UIモデル、スタイル、イベント定義
  - `KeyboardLayoutBuilder.kt`: ソフトキーボードレイアウト構築オーケストレーター
  - `QwertyKeyboardLayout.kt`: QWERTY キーボードレイアウト定義（ベクターアイコン対応絵文字キー含む）
  - `TabletKeyboardLayout.kt`: タブレットキーボードレイアウト定義
  - `NikoKeyboardLayout.kt`: ニコタッチ入力レイアウト & マッパー（ベクターアイコン対応絵文字キー含む）
  - `KeyEventMapper.kt`: 物理キーボード KeyEvent 変換マッパー
  - `KeyAction.kt`: キーボードキーアクション定義
  - `EmojiPaletteController.kt`: ViewPager2 + RecyclerView を用いた絵文字・記号パレット制御（カテゴリ別タブ・グリッド描画、通常/シンボルモード対応）
- **辞書・学習管理・UI**:
  - `DictUtils.kt`: 辞書ファイル操作ユーティリティ
  - `PresetDictActivity.kt`: プリセット辞書管理アクティビティ
  - `UserDictContentActivity.kt`: ユーザー辞書・追加辞書編集アクティビティ
  - `LearningListActivity.kt`: 学習履歴表示・編集アクティビティ
  - `SettingsActivity.kt`: 設定画面 (`PreferenceFragmentCompat`)

---

## 2. IME ライフサイクルとキーボード状態管理

Karukan IME では、IME のライフサイクルイベントに応じてキーボードの表示状態（メインキーボード、絵文字・記号パレット、候補展開ビューなど）を制御する仕組みを備えています。

- **`onWindowHidden()`**: IME ウィンドウが非表示になった際、`KarukanInputView.resetKeyboardState()` を呼び出してパレットや展開候補を非表示状態にします。
- **`onFinishInputView(boolean finishingInput)`**: 入力ビューが終了する際、キーボード表示状態を標準のメインキーボードに復元します。
- **`onFinishInput()`**: 入力セッションの終了時にオーバーレイやパレットを閉じます。
- **`onStartInputView(EditorInfo attribute, boolean restarting)`**: 新しい入力セッションの開始時にキーボード状態をリセットし、メインキーボードとパレット等の重畳表示を防止します。

---

## 3. JNI (Rust / Native) 連携仕様

JNI層 (`src/lib.rs`) で提供されている全ネイティブ関数と、Kotlin層 (`KarukanEngine.kt`) のメソッドは 1 対 1 で対応しています。

| JNI関数名 (`src/lib.rs`) | Kotlin側メソッド (`KarukanEngine.kt`) | 主な機能・役割 |
|---|---|---|
| `nativeInit` | `init { ... }` (初期化) | AIモデル (`.gguf`) のロードと初期化 |
| `nativeLoadSystemDict` | `loadSystemDict()` | システム辞書 (`dict.bin`) のロード |
| `nativeLoadUserDicts` | `loadUserDicts()` | ユーザー辞書・追加辞書のロード |
| `nativeUpdateEngineConfig` | `updateEngineConfig(settings)` | 全設定の一括更新 (推論スレッド数, ストラテジー, チャンク, ビームサーチ, 記号・幅スタイル, 最大学習数) |
| `nativeConvert` | `convert(query, maxCandidates, context, queryId)` | かな漢字変換の候補生成（日付・ユーザー辞書・AIモデル・カタカナ・絵文字・システム辞書・学習履歴の結合とキャンセル判定） |
| `nativeCancelQuery` | `cancelQuery(queryId)` | ライブ変換（リアルタイム推論）のキャンセル |
| `nativeRecordLearning` | `recordLearning(reading, surface)` | 選択された変換候補の学習履歴への記録 |
| `nativeRemoveLearning` | `removeLearningEntry(reading, surface)` | 学習履歴から特定のエントリを削除 |
| `nativeSaveLearning` | `backupLearningToStream` | 学習履歴のバックアップ（TSVファイルストリーム出力） |
| `nativeLoadLearning` | `restoreLearningFromStream` | 学習履歴の復元（TSVファイルを内部学習辞書へ上書きコピーし、ネイティブパースによりカウント・タイムスタンプをロスレスで読み込み） |
| `nativeClearLearning` | `clearLearning()` | メモリ上の学習キャッシュのクリア |
| `nativeRomajiToHiragana` | `romajiToHiragana(query)` | ローマ字→ひらがな変換 |
| `nativeRomajiToHiraganaFlush` | `romajiToHiraganaFlush(query)` | ローマ字→ひらがな変換のフラッシュ（末尾の未確定ローマ字の確定処理） |

### 設定更新 (`nativeUpdateEngineConfig`) で伝達されるパラメータ

`KarukanSettings` の各種設定値は `nativeUpdateEngineConfig` 1 つの JNI 関数を介して Native 側に一括伝達されます。

- **推論パラメータ**: `nThreads`, `strategy`, `maxLatencyMs`, `chunkChars`, `chunkSymbols`, `chunkDigits`, `chunkAlphabets`, `beamWidth`, `beamChars`, `persona`, `useContext`, `contextChars`
- **記号・文字幅スタイル**: `punctuationStyle`, `bracketStyle`, `slashStyle`, `spaceStyle`, `kanaSymbolWidth`, `asciiWidth`, `digitWidth`
- **変換学習設定**: `maxLearningEntries`, `isLearningEnabled`, `isDateConversionEnabled`

---

## 4. ショートカットキー・物理キーボード対応仕様 (Keyboard Shortcuts)

他フロントエンド（fcitx5 / macOS）との互換性を持つ物理キーボードおよびソフトキーボードのショートカット仕様です。

### ファンクションキー変換 (Function Keys)
| キー | 機能 | 動作・処理 |
|---|---|---|
| **F6** | ひらがな変換 | 入力中の文字をひらがなとして確定 (`commitCurrentHiragana`) |
| **F7** | 全角カタカナ変換 | 入力中の文字を全角カタカナとして確定 (`commitKatakanaOrToggleMode`) |
| **F8** | 半角カタカナ変換 | 入力中の文字を半角カタカナとして確定 (`commitHankakuKatakana`) |
| **F9** | 全角英数変換 | 入力中の文字を全角英数として確定 (`commitZenkakuAlphanumeric`) |
| **F10** | 半角英数変換 | 入力中の文字を半角英数として確定 (`commitHankakuAlphanumeric`) |

### Ctrl キーショートカット (Ctrl Shortcuts)
| ショートカット | 機能 | 動作・制御条件 | 仕様区分 |
|---|---|---|---|
| **Ctrl + A** | 全選択 | 編集領域のテキスト全選択 (`selectAllEditingArea` -> `InputConnection.selectAll`) | 共通仕様 |
| **Ctrl + B** | カーソル左移動 | 入力中・候補選択中のみ動作（未入力時は OS/アプリ標準の太字設定等へ委譲） | 共通仕様 |
| **Ctrl + C** | *未割当* | 常時 OS / アプリ標準コピーへ委ねる（IME側では消費しない） | - |
| **Ctrl + D** | 候補全画面展開 / 縮小 | 候補一覧エリアの多段全画面展開・折りたたみ切替 (`toggleCandidateExpand`) | **Android独自** |
| **Ctrl + E** | 行末移動 | 入力中のみ動作 (`moveCursorToEndOfLine`)（未入力時は OS/アプリ標準機能へ委譲） | 共通仕様 |
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
| **Ctrl + Q** | 🔄記号・単漢字ビューオープン | 記号・単漢字変換ビューを直接開く (`activeSourceFilter = SourceFilter.SYMBOL`) | **Android独自** |
| **Ctrl + R** | 🔄記号変換ビューオープン | 記号変換ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.SYMBOL`) | 共通仕様 |
| **Ctrl + S** | 確定取り消し (再変換) | 直前の確定テキストを取り消して未確定入力状態へ復元 (`reconvert`)（未入力時） | **Android独自** |
| **Ctrl + T** | 📝学習ソースビューオープン | 学習ソース絞り込みビューを直接開く (`activeSourceFilter = SourceFilter.LEARNING`) | 共通仕様 |
| **Ctrl + U** | *未割当* | 常時 OS / アプリ標準機能へ委ねる | - |
| **Ctrl + V** | *未割当* | 常時 OS / アプリ標準貼り付けへ委ねる | - |
| **Ctrl + W** | 辞書ソース順巡回 | 辞書ソースフィルターを順巡回切替 (`cycleSourceFilterNext`)（コンポーズ時） | **Android独自** |
| **Ctrl + X** | *未割当* | 常時 OS / アプリ標準切り取りへ委ねる | - |
| **Ctrl + Y** | 詳細表示切替 | AI推論時間・モデル名等の詳細表示をトグル切替 (`onToggleDetailDisplay`) | **Android独自** |
| **Ctrl + Z** | *未割当* | 常時 OS / アプリ標準元に戻す(Undo)等へ委ねる | - |
| **Ctrl + 1 〜 9** | 候補番号選択・確定 | 表示中の 1〜9 番目の候補を直接選択・確定 (`SelectCandidateNumber`) | 共通仕様 |
| **Ctrl + Backspace / Delete** | 候補学習削除 | 選択中の変換候補を学習辞書から削除 (`onDeleteSelectedCandidate`) | 共通仕様 |
| **Ctrl + Shift + L** | ライブ変換トグル | リアルタイムライブ変換の有効/無効切替 (`onToggleLiveConversion`) | 共通仕様 |
| **Ctrl + Shift + V** | 詳細表示切替 | AI推論時間・モデル名等の詳細表示をトグル切替 (`onToggleDetailDisplay`) | 共通仕様 |

  ### Shift キー組み合わせ操作 (Shift Combinations)
  | ショートカット | 機能 | 動作・処理 |
  |---|---|---|
  | **Shift + ← / → / ↑ / ↓** | テキスト範囲選択 | 選択範囲を変更する KeyEvent を送信 (`sendKeyEvent` with `META_SHIFT_ON`) |
  | **Shift + Tab** | 逆方向移動 / 前候補 | 候補選択時は前候補選択、非入力時は逆方向フォーカス移動 |
  | **Shift + Space** | 全角スペース | 非入力時は全角スペースを入力 (`onFullWidthSpace`) |

### JIS配列・特殊物理キー (JIS & Physical Special Keys)
| キー (シンボル名) | 機能 | 動作・処理 |
|---|---|---|
| **変換 (HENKAN)** | 変換 / 次候補 | 変換実行または次の候補を選択 (`onSpaceKey`) |
| **無変換 (MUHENKAN)** | カタカナ変換 | カタカナ変換または入力モード切替 (`commitKatakanaOrToggleMode`) |
| **かな (KANA)** | かなモード切替 | カタカナ/ひらがな入力モード切替 |
| **ひらがな / カタカナ (KATAKANA_HIRAGANA)** | かなモード切替 | カタカナ/ひらがな入力モード切替 |
| **半角/全角 (ZENKAKU_HANKAKU)** | 言語モード切替 | 日本語 / 英語モード切替 (`onToggleLanguageMode`) |
| **英数 (EISU)** | 言語モード切替 | 日本語 / 英語モード切替 (`onToggleLanguageMode`) |
| **Delete (FORWARD_DEL)** | 後方削除 | カーソル後方の1文字削除 (`onDeleteForward`) |
| **Home (MOVE_HOME)** | 先頭移動 | カーソルを行頭（先頭）へ移動 (`moveCursorToStartOfLine`) |
| **End (MOVE_END)** | 末尾移動 | カーソルを行末（末尾）へ移動 (`moveCursorToEndOfLine`) |
| **Escape (ESCAPE)** | キャンセル | 変換・候補選択のキャンセル (`onEscape`) |
| **Tab (TAB)** | 次候補 / フォーカス | 次の変換候補を選択またはフォーカス移動 (`onTabKey`) |
| **PageUp (PAGE_UP)** | ページアップ | 候補一覧または画面のページアップ (`onPageUp`) |
| **PageDown (PAGE_DOWN)** | ページダウン | 候補一覧または画面のページダウン (`onPageDown`) |
| **SYM (SYM)** | 絵文字・記号パレット | 絵文字・記号パレットの表示切替 (`ToggleEmojiPalette`) |

