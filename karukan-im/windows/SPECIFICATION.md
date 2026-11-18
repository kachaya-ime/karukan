# Karukan for Windows フロントエンド仕様書

## 1. 製品概要および動作環境

### 1.1 製品名称・識別情報
- **表示名称**: `Karukan Japanese Input`
- **パッケージ名**: `karukan-im-windows`
- **対象言語**: 日本語 (`LANGID = 0x0411`)
- **ライセンス**: `MIT OR Apache-2.0` デュアルライセンス

### 1.2 動作環境要件
| 項目 | 仕様・要件 |
| :--- | :--- |
| **対応OS** | Windows 11 (x64 / `x86_64-pc-windows-msvc`) |
| **ランタイム依存** | 静的CRT (`/MT`) リンクにより外部 Visual C++ 再頒布可能パッケージ不要 |
| **対応アプリ種別** | Win32 デスクトップアプリ、UWP / WinUI 3 (Immersive) アプリ、システムトレイ統合 |
| **ネットワーク要件** | インストール実行時のみ必須（GitHub Releases からのシステム辞書取得、および Hugging Face Hub からの変換モデル・トークナイザー取得のため）。通常入力時は完全オフライン動作。 |

---

## 2. 入力モードと状態遷移仕様

### 2.1 入力モード定義
TSF のグローバルコンパートメント (`GUID_COMPARTMENT_KEYBOARD_OPENCLOSE` および `GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION`) と連動し、以下の3つの基本入力モードを持つ。

| モード名 | OPENCLOSE | CONVERSIONMODE フラグ | トレイアイコン / 表記 | 動作仕様 |
| :--- | :---: | :--- | :---: | :--- |
| **ひらがなモード**<br/>(初期状態) | `1` (`true`) | `TF_CONVERSIONMODE_NATIVE` (`0x01`) \|<br/>`TF_CONVERSIONMODE_FULLSHAPE` (`0x08`) \|<br/>`TF_CONVERSIONMODE_ROMAN` (`0x10`) | `RES_ID_ICON_HIRAGANA` (`2`)<br/>テキスト: **`あ`** | ローマ字かな変換およびニューラルかな漢字変換（ライブ変換・候補選択）を行う標準モード。 |
| **全角カタカナモード** | `1` (`true`) | 上記 + `TF_CONVERSIONMODE_KATAKANA` (`0x02`) | `RES_ID_ICON_KATAKANA` (`4`)<br/>テキスト: **`カ`** | ローマ字入力されたひらがなプリエディットを即座に全角カタカナへ自動変換するモード。 |
| **直接入力モード**<br/>(半角英数) | `0` (`false`) | `TF_CONVERSIONMODE_ALPHANUMERIC` (`0x00`) | `RES_ID_ICON_ALPHA` (`3`)<br/>テキスト: **`A`** | IME開閉キー以外のすべてのキー入力を消費せず、アプリケーションへ直接スルーする。 |

### 2.2 モード遷移時の動作仕様
- **日本語入力（ひらがな / カタカナ）→ 直接入力への遷移時**:
  - 未確定文字列（Composition）が存在する場合は即座に `EndComposition` で終了し、候補ウィンドウおよび補助テキストを非表示にする。
  - エンジン状態 (`InputMethodEngine::reset`) および周辺文脈 (`set_surrounding_context("", "")`) をクリアする。
- **フォーカス移動時 (`ITfKeyEventSink::OnSetFocus`)**:
  - エンジンに保持されている周辺文脈をリセットする。

---

## 3. キーバインド・入力操作仕様

### 3.1 IME 開閉・入力モード切替キー
以下のキーは TSF `PreservedKey` または `KeyEventSink` によって捕捉される（`Win` キー押下時はすべて無効化しOSショートカットを優先する）。

| 操作目的 | キー入力 | 仮想キーコード (`VK`) | 遷移先・動作 |
| :--- | :--- | :--- | :--- |
| **IME ON/OFF トグル** | `半角/全角` / `漢字` | `VK_KANJI` (`0x19`)<br/>`VK_OEM_AUTO` (`0xF3`)<br/>`VK_OEM_ENLW` (`0xF4`) | 開いている場合は直接入力 (`OFF`) へ、閉じている場合はひらがな (`ON`) へ切り替える。 |
| **IME ON/OFF トグル** | `Alt + ~` (`Alt + 半角/全角`) | `Alt` + `VK_OEM_3` (`0xC0`) | 同上（US配列キーボード向けトグル）。 |
| **直接入力へ切替** | `無変換` / `英数` / `IME Off` | `VK_NONCONVERT` (`0x1D`)<br/>`VK_DBE_ALPHANUMERIC` (`0xF0`)<br/>`VK_IME_OFF` (`0x1A`) | 常に直接入力モード (`OFF`) に切り替える（`Alt` 併用時を除く）。 |
| **日本語入力へ切替 / ひらがな復帰** | `変換` / `かな` / `ひらがな` / `IME On` | `VK_CONVERT` (`0x1C`)<br/>`VK_KANA` (`0x15`)<br/>`VK_DBE_HIRAGANA` (`0xF2`)<br/>`VK_IME_ON` (`0x16`) | ・直接入力時は IME を `ON`（ひらがなモード）にする。<br/>・IME `ON` 時はカタカナモードやエンジン内サブモードからひらがなモードへ復帰させる。 |
| **全角カタカナ切替** | `Ctrl + K` | `VK_CONTROL` + `'K'` (`0x4B`) | IME `ON` 時に全角カタカナモードへ切り替え、プリエディットをカタカナに変換する。 |

### 3.2 プリエディット・変換中キー操作仕様

| 分類 | キー入力 | 動作仕様 |
| :--- | :--- | :--- |
| **文字種一括確定** | `F6` (`0x75`) | プリエディット文字列を **ひらがな** に変換して即座に確定 (`Commit`) する。 |
| | `F7` (`0x76`) | プリエディット文字列を **全角カタカナ** に変換して即座に確定する。 |
| | `F8` (`0x77`) | プリエディット文字列を **半角カタカナ** (`ｶﾅ`) に変換して即座に確定する。 |
| | `F9` (`0x78`) | プリエディット文字列の英数字を **全角英数** (`ａｂｃ１２３`) に変換して即座に確定する。 |
| | `F10` (`0x79`) | プリエディット文字列の英数字を **半角英数** (`abc123`) に変換して即座に確定する。 |
| **編集・変換操作** | `Space` | 変換候補の生成・次候補選択。 |
| | `Shift + Space` | かな入力中に空白（設定 `[symbol] space` に従い半角または全角、未入力時は常に全角空白）を挿入。 |
| | `Enter` (`0x0D`) | 現在のプリエディットまたは選択候補を確定し、学習履歴 (`learning.tsv`) を保存する。 |
| | `Escape` (`0x1B`) | 変換キャンセル / 入力状態のリセット。 |
| | `BackSpace` (`0x08`) / `Delete` (`0x2E`) | カーソル位置の前 / 後ろの1文字を削除。 |
| | `←` `→` `↑` `↓` / `Home` `End` / `PgUp` `PgDn` | キャレット移動、文節区切り調整、候補リストの選択移動・ページ送り。 |
| | `Tab` / `Shift + Tab` | 候補選択移動。 |
| **エンジン制御** | `Ctrl + Shift + L` | ライブ変換の有効 / 無効を実行中にトグルする。 |
| | `Ctrl + Shift + V` | 候補ウィンドウ下部（補助テキスト）の詳細表示（推論時間・使用モデル名・文脈表示）をトグルする。 |
| | `Ctrl + I` / `Ctrl + K` / `Ctrl + R` / `Ctrl + T` / `Ctrl + J` | エンジン定義の各種変換・日時ビュー (`Ctrl+R`) 等のショートカットを実行する。 |

---

## 4. 画面・UI仕様

### 4.1 プリエディット（未確定文字列）表示仕様
- アプリケーションのテキスト入力位置に TSF Composition (`ITfComposition`) としてインライン表示する。
- 未確定範囲には `GUID_PROP_COMPOSING` プロパティ (`VT_I4 = 1`) を付与する。
- キャレット位置は UTF-8 文字インデックスから UTF-16 サロゲートペアを考慮したコードユニット数へ変換し、`ITfContext::SetSelection` で正確に位置付ける。
- 新規入力開始時、カーソル左側の確定済みテキスト最大 **64 UTF-16文字** を自動取得し、設定の `[conversion] use_context = true` 時にAI変換の左文脈として利用する。

### 4.2 候補ウィンドウ (`KarukanCandidatePopup`) 仕様

| 項目 | 仕様値・デザイン |
| :--- | :--- |
| **ウィンドウサイズ** | 幅: `420px`（固定）<br/>リスト高さ: `30px × 候補数`（最小 `30px`、最大 `360px` = 最大12行分）<br/>フッター（補助テキスト）高さ: `38px`<br/>全体高さ: `リスト高さ + 38px + 4px` |
| **ウィンドウ特性** | 最前面 (`HWND_TOPMOST`)、非アクティブ (`WS_EX_NOACTIVATE`)、ツールウィンドウ (`WS_EX_TOOLWINDOW`)、Windows 11 角丸 (`DWMWCP_ROUND`) |
| **フォント** | 候補リスト: `Yu Gothic UI` 14pt (DirectWrite) / `-15px` (GDI)<br/>補助テキスト: `Yu Gothic UI` `-12px` |
| **カラーパレット** | ・ウィンドウ外枠: `#D0D0D0`<br/>・通常行背景: `#FFFFFF` / 選択行背景: `#EBF3FB` (上下 `2px`・左右 `4px` インセット)<br/>・選択行左端アクセントバー: `#0067C0` (幅 `3px`)<br/>・候補番号 (`1.`): 選択時 `#005FB8` / 非選択時 `#707070` (`X = 14px`)<br/>・候補テキスト: 選択時 `#0F0F0F` / 非選択時 `#1F1F1F` (`X = 40px`)<br/>・品詞・補足説明: `#808080` (`X = min(48 + 単語描画幅, 右端 - 120px)`)<br/>・フッター背景: `#F8F8F8` / フッター上部境界線: `#E5E5E5` / フッター文字色: `#555555` |
| **カラー絵文字描画** | Direct2D (`ID2D1DCRenderTarget`) + DirectWrite (`D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT`) によりフルカラー絵文字を描画。 |
| **表示位置・画面端制御** | 未確定文字列の矩形下端 (`rect.bottom + 4px`) を基準とする。<br/>マルチモニターの作業領域 (`rcWork`) に対し、右端超過時は左シフト、下端超過時は未確定文字列の上側 (`rect.top - height - 4px`) に自動反転表示する。 |

### 4.3 システムトレイ（言語バー）仕様
- タスクバーのシステムトレイ領域に現在の入力モードアイコン（`あ` / `カ` / `A`）を表示し、ホバー時にツールチップ `"Karukan Japanese Input"` を表示する。
- **左クリック**: IME の ON (`あ`) / OFF (`A`) を即座に切り替える。
- **右クリック**: コンテキストメニューを表示する。
  - `ひらがな (Hiragana)`（現在選択中の場合チェックマーク表示）
  - `全角カタカナ (Katakana)`（現在選択中の場合チェックマーク表示）
  - `直接入力 / 半角英数 (Direct)`（現在選択中の場合チェックマーク表示）
  - `---`（区切り線）
  - `設定 (Settings)...`（クリックで `karukan-settings.exe` を起動）

---

## 5. 設定アプリケーション (`karukan-settings.exe`) 仕様

### 5.1 ウィンドウ全体仕様
- **ウィンドウタイトル**: `Karukan 設定`
- **レイアウト**: 左サイドバー（幅 `200px`、項目高さ `40px`）＋ 右メインコンテンツ領域（マージン `20px`、縦スクロール対応）＋ 下部固定フッター（ステータス表示ラベル＋操作ボタン）。
- **設定の反映タイミング**: 設定ファイルやユーザー辞書を保存した後、Windows から一度サインアウトして再サインイン（またはIME使用中アプリを再起動）することで反映される。

### 5.2 カテゴリ一覧と編集項目仕様

サイドバーには以下の10カテゴリを表示する。カテゴリを切り替えても未保存の編集内容はメモリ上に保持される。

#### (1) 設定編集カテゴリ（7カテゴリ）
| カテゴリ名 | TOMLセクション | 主な設定項目と既定値 | UIコントロール種別 |
| :--- | :--- | :--- | :--- |
| **変換** | `[conversion]` | ・`strategy`: `"adaptive"` (`adaptive` / `light` / `main`)<br/>・`num_candidates`: `9`<br/>・`use_context`: `true`<br/>・`context_chars`: `10`<br/>・`persona`: `""`<br/>・`chunk_chars`: `30`, `chunk_symbols`: `1`, `chunk_digits`: `0`, `chunk_alphabets`: `0`<br/>・`beam_chars`: `30`, `beam_width`: `3`<br/>・`max_latency_ms`: `100`, `timeout_ms`: `500`<br/>・`model`: `"jinen-v2-small-q8"`, `light_model`: `"jinen-v2-xsmall-q8"`<br/>・`n_threads`: `4`<br/>・`live_conversion`: `true` | ラジオボタン (`strategy`)<br/>チェックボックス (`bool` 項目)<br/>1行テキスト入力 (`数値` / `文字列`) |
| **学習** | `[learning]` | ・`enabled`: `true`<br/>・`max_entries`: `10000`<br/>・`max_surface_chars`: `50` | チェックボックス<br/>1行テキスト入力 |
| **表示** | `[display]` | ・`verbose`: `false`<br/>・`candidate_window`: `"always"` (`always` / `conversion`) | チェックボックス<br/>ラジオボタン |
| **記号・空白** | `[symbol]` | ・`punctuation`: `"、。"` (`、。` / `，．` / `、．` / `，。`)<br/>・`bracket`: `"「」"` (`「」` / `[]`)<br/>・`slash`: `"・"` (`・` / `/`)<br/>・`space`: `"half"` (`half` / `full`) | ラジオボタン |
| **文字幅** | `[width]` | ・`kana_symbol`: `"full"` (`half` / `full`)<br/>・`ascii_symbol`: `"full"` (`half` / `full`)<br/>・`digit`: `"half"` (`half` / `full`) | ラジオボタン |
| **日付・時刻** | `[date]` | ・`formats`: 日付フォーマット配列<br/>・`phrases`: 読みとオフセット日数・専用フォーマットの配列 | 複数行TOMLエディタ (`高さ 104px`) |
| **変換モデル** | `[models]` | ・モデルキーと Hugging Face リポジトリ (`{ repo, filename }`) またはローカルGGUFパスのテーブル | 複数行TOMLエディタ |

- **バリデーション仕様**: 「保存」押下時に入力値を TOML にシリアライズし、`karukan_im::config::Settings` 構造体でのデシリアライズ検証を行う。型エラーや不正な値がある場合はエラーダイアログを表示し、既存ファイルを破壊しない。

#### (2) 学習履歴カテゴリ (`learning_records`)
- **一覧表示**:
  - 列構成: `読み` | `表記` | `選択回数` | `最終選択`（ローカル日時 `YYYY-MM-DD HH:MM:SS` 表示）
  - ソート機能: 列見出しクリックでその列を基準にソート（`▲` 昇順 / `▼` 降順トグル）。
  - 選択方式: `Ctrl` / `Shift` キーによる複数行選択に対応。
- **削除機能**:
  - **「選択した履歴を削除」**: 選択した行（読み $R$、表記 $S$）を削除する。さらに、**より長い読み $R'$ ($R'$ が $R$ で始まる) の記録のうち、表記 $S'$ に $S$ が含まれる連動エントリも一括削除**し、長文変換時に同じ誤学習が再浮上するのを防ぐ。
  - **「すべて削除」**: 全学習履歴を消去する。
  - いずれの削除操作も、実行前に確認ダイアログ（IME使用中アプリを閉じる旨の案内付き）を表示し、承認後に `learning.tsv` へアトミック保存する。

#### (3) ユーザー辞書カテゴリ (`user_dictionary`)
- **一覧表示**: `読み` | `表記` | `品詞` | `コメント` の4列リストビュー。
- **編集フォーム**:
  - 入力欄: `読み`（必須）、`表記`（必須）、`品詞`（省略時は `名詞`）、`コメント`（任意）。
  - 行を選択すると入力欄へ値が読み込まれ、編集して追加・更新・削除が可能。
- **保存仕様**:
  - 「辞書を保存」ボタンで `%APPDATA%\karukan\karukan-im\data\user_dicts\karukan-user.tsv` へ Mozc / Google 日本語入力互換の4カラム TSV 形式で保存する。
  - `user_dicts` ディレクトリ内の他ファイル（ユーザーが手動配置した別辞書など）は変更しない。

#### (4) ライセンスカテゴリ (`license`)
- `MIT License` および `Apache License 2.0` の全文を読み取り専用複数行テキストボックスに表示する。

---

## 6. インストーラー・パッケージング仕様

### 6.1 CLI 仕様 (`install.exe`)
管理者権限で実行する。終了コードは成功時 `0`、失敗時 `非0`。

| コマンド | 実行内容 |
| :--- | :--- |
| `install.exe install` | 1. `install.exe` と同じフォルダにある `karukan_im_windows.dll` と `karukan-settings.exe` を検証。<br/>2. `%LOCALAPPDATA%\Programs\Karukan\` を作成し、ロックされていない旧 `karukan_im_windows-*.dll` を清掃した上で、`karukan_im_windows-<PID>-<UnixNanoTimestamp>.dll` と `karukan-settings.exe` を配置。<br/>3. `%APPDATA%\karukan\karukan-im\config\config.toml` が未存在なら既定設定を生成。<br/>4. システム辞書 `dict.bin` が未存在なら `curl.exe` と `tar.exe` で GitHub Releases (`dict.tgz`) から取得・展開し、一時ファイル経由のアトミックリネームで配置。<br/>5. 設定の `strategy` に応じ、必要なすべての変換モデル (`.gguf`) と `tokenizer.json` を Hugging Face Hub から事前取得。<br/>6. `HKLM\Software\Classes\CLSID\{7d7a2c6e-4d65-4ab8-8f30-4a1f2c83f81b}` に COM InprocServer32 (`ThreadingModel = Apartment`) を登録。<br/>7. TSF プロファイル (`0x0411`) と8つのサポートカテゴリを登録・有効化（失敗時は COM・TSF 登録を自動ロールバック）。<br/>8. `Set-WinDefaultInputMethodOverride` により Karukan を既定の日本語入力方式に設定。 |
| `install.exe uninstall` | 1. `Set-WinDefaultInputMethodOverride -InputTip ''` で既定入力方式のオーバーライドを解除。<br/>2. TSF 言語プロファイルの無効化・削除、全カテゴリの登録解除、input processor の登録解除。<br/>3. `HKCU\Software\Microsoft\CTF\TIP\{CLSID}` および `HKLM` / `HKCU` の `Software\Classes\CLSID\{CLSID}` レジストリツリーを削除。<br/>4. `%LOCALAPPDATA%\Programs\Karukan\` 内の DLL は実行中プロセス保護のため残置し、サインアウト後の削除を案内する。 |

### 6.2 配布パッケージ仕様 (`package-windows.ps1`)
- 生成パス: `karukan-im\windows\dist\karukan-windows-x64-v<version>.zip`
- 同梱ファイル:
  - `install.exe`
  - `karukan_im_windows.dll`
  - `karukan-settings.exe`
  - `README.txt`（インストール・アンインストール手順書）
  - `LICENSE-MIT`
  - `LICENSE-APACHE`

---

## 7. データファイル・フォーマット仕様

| ファイル種別 | パス | フォーマット仕様 |
| :--- | :--- | :--- |
| **設定ファイル** | `%APPDATA%\karukan\karukan-im\config\config.toml` | UTF-8 TOML。保存時は同一ディレクトリに `.tmp` を書き出し `MoveFileExW(MOVEFILE_REPLACE_EXISTING \| MOVEFILE_WRITE_THROUGH)` でアトミック更新。 |
| **システム辞書** | `%APPDATA%\karukan\karukan-im\data\dict.bin` | Karukan バイナリトライ辞書形式 (`KRKN`)。 |
| **学習履歴** | `%APPDATA%\karukan\karukan-im\data\learning.tsv` | UTF-8 TSV。<br/>ヘッダー・コメント行 (`#`) を除き、各行は `読み<TAB>表記<TAB>選択回数(u32)<TAB>最終アクセスUNIX秒(u64)`（または3カラム互換形式）。 |
| **ユーザー辞書** | `%APPDATA%\karukan\karukan-im\data\user_dicts\karukan-user.tsv` | UTF-8 TSV（Mozc互換）。<br/>各行は `読み<TAB>表記<TAB>品詞<TAB>コメント`。制御文字（改行・タブ・CR・NUL）を含む入力はバリデーションで拒否する。 |
| **動作ログ** | `%LOCALAPPDATA%\karukan\karukan-im\logs\windows-tsf.log` | UTF-8 テキスト（ANSIエスケープなし、追記モード）。<br/>環境変数 `RUST_LOG` でログレベル制御可能（デフォルト: `info`）。 |
