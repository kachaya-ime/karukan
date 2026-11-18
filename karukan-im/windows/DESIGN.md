# Karukan for Windows フロントエンド設計書

## 1. 概要

### 1.1 目的
本書は、日本語IMEエンジン「Karukan」(`karukan-im` / `karukan-engine`) を Windows 11 (x64) 上で利用可能にする **Text Services Framework (TSF) フロントエンド** (`karukan-im-windows`) のアーキテクチャおよび詳細設計を定義する。

### 1.2 スコープと成果物
Windowsフロントエンドクレート (`Cargo.toml`) は、以下の3つの主要バイナリで構成される。

| 成果物 | 種別 | 役割 | 主要エントリポイント |
| :--- | :--- | :--- | :--- |
| `karukan_im_windows.dll` | COM In-Proc Server (`cdylib`) | TSF Text Input Processor (TIP) 本体。各ターゲットプロセスにロードされ、キーフック・かな漢字変換・プリエディット操作・候補ウィンドウ表示・言語バー連携を行う。 | `src/lib.rs`, `src/tsf/mod.rs` |
| `install.exe` | CLI インストーラー (`bin`) | DLLのバージョン付き配置、レジストリ (COM CLSID) 登録、TSF プロファイル/カテゴリ登録、システム辞書・LLMモデル (`tokenizer.json` 含む) の事前取得を行う。 | `src/bin/install.rs` |
| `karukan-settings.exe` | Win32 GUI アプリ (`bin`) | 設定 (`config.toml`) のGUI編集、学習履歴 (`learning.tsv`) の閲覧・削除、Mozc互換ユーザー辞書 (`karukan-user.tsv`) の管理、ライセンス表示を行う。 | `src/bin/settings.rs` |

---

## 2. システムアーキテクチャ

### 2.1 コンポーネント構成図

```mermaid
flowchart TB
    subgraph AppProcess["クライアントアプリケーションプロセス (Notepad, ブラウザ等)"]
        TSF["Windows TSF Manager<br/>(ITfThreadMgr / ITfContext)"]
        subgraph TIP["karukan_im_windows.dll (In-Process COM)"]
            CF["ClassFactory<br/>(IClassFactory)"]
            TS["TextService<br/>(ITfTextInputProcessor)"]
            KH["KeyHandler<br/>(ITfKeyEventSink)"]
            ES["EditSession / CompositionSink<br/>(ITfEditSession / ITfCompositionSink)"]
            CS["CompartmentSink<br/>(ITfCompartmentEventSink)"]
            LB["LangBarButton<br/>(ITfLangBarItemButton)"]
            CW["CandidatePopup<br/>(Win32 Topmost Non-Activate Window + Direct2D)"]
        end
        Core["共有IMEコア<br/>(karukan_im::InputMethodEngine)"]
    end

    subgraph ExternalTools["管理・補助ツール"]
        INST["install.exe<br/>(COM・TSF登録 / モデル・辞書取得)"]
        SET["karukan-settings.exe<br/>(Win32 GUI 設定・学習履歴・辞書管理)"]
    end

    subgraph Storage["ファイルシステム (%APPDATA% / %LOCALAPPDATA%)"]
        CFG["config.toml<br/>(設定ファイル)"]
        DICT["dict.bin / user_dicts/*.tsv<br/>(システム・ユーザー辞書)"]
        LEARN["learning.tsv<br/>(学習キャッシュ)"]
        HF["huggingface/hub<br/>(ニューラル変換モデルキャッシュ)"]
        LOG["windows-tsf.log<br/>(動作ログ)"]
    end

    TSF <-->|COM Interface| TS
    TSF <-->|Key Events| KH
    TSF <-->|Read/Write Locks| ES
    TSF <-->|Compartment Events| CS
    TSF <-->|Systray / LangBar| LB

    KH -->|process_key / set_surrounding_context| Core
    KH -->|RequestEditSession| ES
    ES -->|Show / Hide / UpdateAux| CW
    CS -->|Mode Sync| Core
    CS -->|OnUpdate| LB
    LB -->|Mode Change / Spawn| SET

    Core <--> CFG
    Core <--> DICT
    Core <--> LEARN
    Core <--> HF
    TIP --> LOG
    INST --> DICT
    INST --> HF
    INST --> CFG
    SET <--> CFG
    SET <--> LEARN
    SET <--> DICT
```

### 2.2 ビルド方針（静的CRTリンク）
- クライアントアプリケーションの環境に依存せず任意のプロセスへ安全にインプロセスDLLをロードさせるため、RustおよびC/C++依存ライブラリ（`llama.cpp` 等）は静的CRT (`/MT`) でリンクする（`build-windows.ps1`）。

---

## 3. TSF識別子とカテゴリ設計

`src/lib.rs` で定義される各種GUIDおよび定数は以下の通り。

### 3.1 基本GUID
- **`CLSID_KARUKAN_TIP`**: `7d7a2c6e-4d65-4ab8-8f30-4a1f2c83f81b` (COM クラスID)
- **`GUID_KARUKAN_PROFILE`**: `9d8e6e3a-8f6d-45d3-a0b1-9b1e391f01a1` (TSF 言語プロファイルID、言語: `0x0411` 日本語)
- **`GUID_PRESERVEDKEY_TOGGLE`**: `7d7a2c6e-4d65-4ab8-8f30-4a1f2c83f81c` (IME ON/OFF トグル用予約キーGUID)

### 3.2 サポートするTSFカテゴリ (`ITfCategoryMgr`)
Windows 11 のデスクトップアプリ、UWP / WinUI 3 (Immersive) アプリ、システムトレイ統合に対応するため、以下のカテゴリに登録する。
- `GUID_TFCAT_TIP_KEYBOARD`: キーボードTIP
- `GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER`: 表示属性プロバイダ
- `GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT`: システムトレイ（言語バー）サポート
- `GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT`: Windows 8/10/11 モダンアプリ (UWP / WinUI) 対応
- `GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT`: 入力モードCompartment対応
- `GUID_TFCAT_TIPCAP_UIELEMENTENABLED`: UIElement対応
- `GUID_TFCAT_TIPCAP_SECUREMODE`: セキュアモード対応
- `GUID_TFCAT_TIPCAP_COMLESS`: COMレスアクティベーション対応

---

## 4. モジュール詳細設計

### 4.1 COM エントリポイント (`src/tsf/mod.rs`)
- **`DllGetClassObject`**:
  - 初回呼び出し時に `Once` でロガー (`tracing_subscriber`) を初期化し、`%LOCALAPPDATA%\karukan\karukan-im\logs\windows-tsf.log` へプロセスID・実行ファイル名付きでログを出力する。
  - 要求された `CLSID` が `CLSID_KARUKAN_TIP` と一致する場合、`ClassFactory` (`IClassFactory`) を返す。
- **`ClassFactory::CreateInstance`**:
  - アグリゲーション (`outer != null`) は拒否 (`CLASS_E_NOAGGREGATION`) し、`TextService` インスタンスを生成して `ITfTextInputProcessor` 等へ QueryInterface する。
- **`DllCanUnloadNow`**:
  - 常に `S_FALSE` を返し、ホストプロセス終了までDLLのアンロードを抑止してスレッドローカルリソースやフックの安全性を保つ。

### 4.2 テキストサービス管理 (`src/tsf/text_service.rs`)
`TextService` は `ITfTextInputProcessor` を実装し、TSFスレッドごとのライフサイクルを管理する。

#### `Activate(thread_mgr, thread_id)`
1. **モデルキャッシュパスの構成**: 環境変数 (`HF_HUB_CACHE`, `HF_HOME` 等) が未設定の場合、`%LOCALAPPDATA%\huggingface\hub` を `HF_HUB_CACHE` に設定する。
2. **エンジン初期化**: `Settings::load_or_default()` から設定を読み込み、`InputMethodEngine` を生成・初期化して `Arc<Mutex<InputMethodEngine>>` に格納する。
3. **キーイベントシンク登録**: `KeyHandler` を生成し、`ITfKeystrokeMgr::AdviseKeyEventSink` でフォアグラウンドシンクとして登録する。
4. **予約キー (`PreservedKey`) の登録**:
   - `VK_KANJI` (`0x19`), `VK_OEM_AUTO` (`0xF3`), `VK_OEM_ENLW` (`0xF4`), `Alt + VK_OEM_3` (`0xC0`) を `GUID_PRESERVEDKEY_TOGGLE` として登録し、IMEの開閉トグルを確実に捕捉する。
5. **Compartment イベント監視と初期化**:
   - グローバル Compartment の `GUID_COMPARTMENT_KEYBOARD_OPENCLOSE` (IME ON/OFF) と `GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION` (変換モード) に `CompartmentSink` を接続。
   - 初期状態を Open (`true`)、変換モードを `TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN`（ひらがなモード）に設定。
6. **言語バーボタン登録**:
   - `ITfLangBarItemMgr::AddItem` を通じて `LangBarButton` をシステムトレイに登録する。

#### `Deactivate()`
1. 登録した `PreservedKey` の解除 (`UnpreserveKey`) と `KeyEventSink` の解除 (`UnadviseKeyEventSink`)。
2. `CompartmentSink` の `UnadviseSink` と `LangBarButton` の `RemoveItem`。
3. **`engine.save_learning()` の呼び出し**: スレッド終了・非アクティブ化時に学習履歴キャッシュをディスクへ永続化する。

---

### 4.3 キー入力処理 (`src/tsf/key_handler.rs`)
`KeyHandler` は `ITfKeyEventSink` を実装し、Windows の仮想キー (`VK_*`) とスキャンコードを Karukan コアの `KeyEvent` (`Keysym`, `KeyModifiers`) に変換してディスパッチする。

#### 4.3.1 キー入力シーケンス

```mermaid
sequenceDiagram
    participant App as アプリ / TSF (ITfKeystrokeMgr)
    participant KH as KeyHandler (ITfKeyEventSink)
    participant Comp as Compartment
    participant Eng as InputMethodEngine
    participant ES as EditSession (ITfEditSession)
    participant CW as CandidatePopup

    App->>KH: OnTestKeyDown(context, wparam, lparam)
    KH->>Comp: 開閉状態・モード確認
    KH-->>App: BOOL(1: IMEが消費 / 0: アプリへスルー)

    opt BOOL(1) の場合
        App->>KH: OnKeyDown(context, wparam, lparam)
        alt トグルキー / 開閉キー (半角/全角, 無変換, かな 等)
            KH->>Comp: IME開閉・変換モード更新
            opt IMEクローズ時
                KH->>ES: RequestEditSession(未確定文字列破棄・候補非表示・Engineリセット)
            end
        else ファンクションキー (F6〜F10: プリエディット存在時)
            KH->>Eng: プリエディット文字列取得 & リセット
            KH->>KH: convert_function_key (ひらがな/全角カナ/半角ｶﾅ/全角英数/半角英数)
            KH->>ES: RequestEditSession(Commit + HideCandidates)
        else 通常キー入力
            opt 新規コンポジション開始前
                KH->>ES: capture_surrounding_text (カーソル左最大64文字取得)
                KH->>Eng: set_surrounding_context(left_context, "")
            end
            KH->>Eng: process_key(KeyEvent)
            Eng-->>KH: KeyProcessResult (actions, consumed)
            opt カタカナモード時の自動カナ変換
                KH->>Eng: process_key(Ctrl+K) 合成
            end
            opt actions が空でない場合
                KH->>ES: context.RequestEditSession(actions)
                ES->>App: StartComposition / SetText / EndComposition / SetSelection
                ES->>CW: show() / hide() / set_aux()
            end
            opt Commit アクションが含まれる場合
                KH->>Eng: save_learning()
            end
        end
        KH-->>App: BOOL(consumed)
    end
```

#### 4.3.2 特殊キー・モード制御仕様
| キー種別 | 対象仮想キー | 動作 |
| :--- | :--- | :--- |
| **IMEトグル** | `VK_KANJI` (`0x19`), `VK_OEM_AUTO` (`0xF3`), `VK_OEM_ENLW` (`0xF4`), `Alt + ~` (`0xC0`) | IMEのON/OFFを切り替える。OFF遷移時はアクティブなCompositionを終了しエンジンをリセットする。 |
| **直接入力 (IME OFF)** | `VK_NONCONVERT` (無変換), `VK_DBE_ALPHANUMERIC` (英数), `VK_IME_OFF` (`0x1A`) | IMEをOFF (`TF_CONVERSIONMODE_ALPHANUMERIC`) に切り替える。 |
| **日本語入力 (IME ON / ひらがな復帰)** | `VK_CONVERT` (変換), `VK_KANA` (かな), `VK_DBE_HIRAGANA` (`0xF2`), `VK_IME_ON` (`0x16`) | IMEがOFFならONにする。ON時はひらがなモードへ戻し、エンジンの `Keysym::HENKAN` を送出してサブモード復帰を行う。 |
| **文字種一括変換** | `F6` (`0x75`) 〜 `F10` (`0x79`) | プリエディット中に押下された場合、F6:ひらがな、F7:全角カタカナ、F8:半角ｶﾀｶﾅ、F9:全角英数、F10:半角英数に変換して即座に確定 (`Commit`) する。 |
| **エンジンショートカット** | `Ctrl + I/K/R/T/J`, `Ctrl + Shift + L/V` | `ToUnicodeEx` が制御文字を返すケースを回避し、ASCII Keysym としてエンジンへ渡し、カタカナ変換や詳細表示切替 (`Ctrl+Shift+V`) 等を実行させる。 |

---

### 4.4 ドキュメント編集セッション (`src/tsf/edit_session.rs`)
TSFではドキュメント (`ITfContext`) の読み書きは必ず `ITfEditSession::DoEditSession(edit_cookie)` 内で実行する必要がある。`EDIT_SESSION_FLAGS` には `TF_ES_ASYNCDONTCARE | TF_ES_READWRITE` (`7`) を使用する。

1. **文脈取得 (`CaptureContextSession`)**:
   - 変換開始前（コンポジション未開始時）に現在の選択範囲 (`GetSelection`) の開始アンカーから前方へ最大64 UTF-16コードユニット (`ShiftStart(-64)`) のテキストを取得し、LLM変換エンジンの左文脈 (`set_surrounding_context`) として渡す。
2. **プリエディット更新 (`update_preedit`)**:
   - 文字列が空なら既存の `ITfComposition::EndComposition` を呼んで終了する。
   - 未開始なら `ITfContextComposition::StartComposition` でコンポジションを開始し、`CompositionSink` (`ITfCompositionSink`) を登録して外部要因による強制終了 (`OnCompositionTerminated`) を追跡する。
   - コンポジション範囲にテキスト (`SetText`) と `GUID_PROP_COMPOSING` プロパティ (`1`) を設定し、UTF-8文字単位のキャレット位置をUTF-16オフセットに換算して `SetSelection` でカーソル位置を移動する。
3. **確定 (`commit_text`)**:
   - コンポジションが存在する場合はその範囲を確定文字列で上書きし、キャレットを末尾に移動してから `EndComposition` を呼ぶ。
   - コンポジションが存在しない直接確定時は `ITfInsertAtSelection::InsertTextAtSelection` で挿入する。

---

### 4.5 候補ウィンドウ (`src/tsf/candidate_window.rs`)
変換候補および補助テキスト（ページ番号・読み・モデル情報など）を表示する独自の非アクティブポップアップウィンドウ (`CandidatePopup`)。

#### 4.5.1 ウィンドウ構造とスタイル
- **トップレベルウィンドウ (`KarukanCandidatePopup`)**:
  - 拡張スタイル: `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST`
  - スタイル: `WS_POPUP`
  - `WM_MOUSEACTIVATE` で常に `MA_NOACTIVATE` を返し、入力中のターゲットアプリからフォーカスを奪わない。
  - Windows 11 DWM API (`DwmSetWindowAttribute` + `DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND`) により角丸ウィンドウとして描画。
- **子コントロール**:
  - 候補リスト (`LISTBOX`): オーナードロー固定高さ (`LBS_OWNERDRAWFIXED`, 1行 `30px`、最大高さ `360px`、幅 `420px`)。
  - フッター補助領域 (`STATIC`): 高さ `38px`、背景色 `#F8F8F8`、上部区切り線 `#E5E5E5`。

#### 4.5.2 Direct2D / DirectWrite によるテキスト＆カラー絵文字描画
- GDI の `TextOutW` ではカラー絵文字が白黒または豆腐になるため、スレッドローカルな `D2DResources` (`ID2D1DCRenderTarget` + `IDWriteFactory`) を用いて `WM_DRAWITEM` の HDC にバインドする。
- `D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT` オプションを有効化し、`Yu Gothic UI` (14pt) で以下の3要素を色分け・位置揃えして描画する（Direct2D初期化失敗時はGDIへ自動フォールバック）：
  1. **候補番号 (`1.`)**: 選択時 `#005FB8` / 非選択時 `#707070` (左オフセット `14px`)
  2. **候補文字列**: 選択時 `#0F0F0F` / 非選択時 `#1F1F1F` (左オフセット `40px`、`CreateTextLayout::GetMetrics` で実描画幅を計測)
  3. **補足説明・品詞注釈**: `#808080` (候補文字列の右側に動的配置)
- 選択行には淡いアクセントブルー背景 (`#EBF3FB`) と左端の縦アクセントバー (`#0067C0`) を描画する。

#### 4.5.3 マルチモニター対応座標計算
- `ITfContextView::GetTextExt` でコンポジション範囲のスクリーン矩形 (`rect`) を取得。
- `MonitorFromPoint(MONITOR_DEFAULTTONEAREST)` と `GetMonitorInfoW` で対象モニターの作業領域 (`rcWork`) を取得。
- 候補ウィンドウが画面右端を超える場合は左へ寄せ、画面下端 (`rcWork.bottom`) を超える場合はキャレットの上側 (`rect.top - height - 4`) に反転配置（フリップ）する。

---

### 4.6 Compartment 連携とシステムトレイ言語バー (`src/tsf/compartment.rs`, `src/tsf/lang_bar.rs`)

#### 4.6.1 Compartment 双方向同期 (`CompartmentSink`)
- OSや他コンポーネントから `GUID_COMPARTMENT_KEYBOARD_OPENCLOSE` または `GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION` が変更された際、`ITfCompartmentEventSink::OnChange` で検知する。
- IMEがクローズされた場合は `engine.reset()` を呼び、変換モードがカタカナ/ひらがなに変更された場合はエンジン側へ `Ctrl+K` / `HENKAN` イベントを送出して内部状態を一致させる。
- 変更後、`ITfLangBarItemSink::OnUpdate` を呼び出してシステムトレイアイコンとテキストを即座に再描画する。

#### 4.6.2 システムトレイアイコン (`LangBarButton`)
- `ITfLangBarItemButton` および `ITfSource` を実装し、`GUID_LBI_INPUTMODE` (`TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWNINTRAY`) としてタスクバーに表示する。
- **アイコン・テキスト表示**:
  - ひらがなモード: アイコン `RES_ID_ICON_HIRAGANA` (`2`) / テキスト `"あ"`
  - 全角カタカナモード: アイコン `RES_ID_ICON_KATAKANA` (`4`) / テキスト `"カ"`
  - 直接入力（半角英数）モード: アイコン `RES_ID_ICON_ALPHA` (`3`) / テキスト `"A"`
- **クリック操作**:
  - **左クリック (`TF_LBI_CLK_LEFT`)**: IME の ON / OFF をトグルする。
  - **右クリック (`TF_LBI_CLK_RIGHT`)**:
    - UWP / WinUI 3 アプリ（Windows 11 メモ帳など）がアクティブな場合でもコンテキストメニューが確実にフォーカスを得て閉じられるよう、`AttachThreadInput` でフォアグラウンドスレッドと一時結合した上で `SetForegroundWindow` と `TrackPopupMenu` を実行し、終了後に `WM_NULL` をポストする（KB Q139526 準拠）。
    - メニュー項目:
      1. `ひらがな (Hiragana)`
      2. `全角カタカナ (Katakana)`
      3. `直接入力 / 半角英数 (Direct)`
      4. セパレータ
      5. `設定 (Settings)...` → `%LOCALAPPDATA%\Programs\Karukan\karukan-settings.exe` を非同期起動 (`spawn_settings_app`)。

---

## 5. インストーラー設計 (`src/bin/install.rs`)

管理者権限の PowerShell から `install.exe install` / `install.exe uninstall` として実行される。

### 5.1 インストールフロー (`install`)
1. **バイナリ配置（ロック回避バージョニング）**:
   - `%LOCALAPPDATA%\Programs\Karukan` に `karukan-settings.exe` をコピーする。
   - DLL は使用中のプロセスによるファイルロック（上書き失敗）を防ぐため、`karukan_im_windows-<version>-<timestamp>.dll` の形式でユニークなファイル名としてコピーする。
2. **初期設定ファイル生成**:
   - `%APPDATA%\karukan\karukan-im\config\config.toml` が存在しない場合、デフォルト設定 (`default.toml`) を書き出す（既存ファイルは保持）。
3. **システム辞書の自動取得**:
   - `%APPDATA%\karukan\karukan-im\data\dict.bin`（または設定の `[conversion] dict_path`）が存在しない場合、GitHub Releases から `dict.tgz` をダウンロード・展開して配置する。
4. **LLMモデル・トークナイザーの事前取得**:
   - 設定ファイルで指定されているメイン変換モデル（および `Adaptive` 戦略時の軽量モデル）と `tokenizer.json` を Hugging Face Hub から `%LOCALAPPDATA%\huggingface\hub` へダウンロードし、IME初回起動時のフリーズを防止する。
5. **COM レジストリ登録 (`HKLM`)**:
   - `HKLM\Software\Classes\CLSID\{CLSID_KARUKAN_TIP}` に表示名、`InProcServer32`（バージョン付きDLLのフルパス）、`ThreadingModel = "Apartment"` を登録する。
6. **TSF プロファイル・カテゴリ登録**:
   - `ITfInputProcessorProfileMgr::RegisterProfile` (失敗時は `ITfInputProcessorProfiles` にフォールバック) で日本語 (`0x0411`) のキーボードプロファイルを登録し、ブランドアイコン (`RES_ID_ICON_BRANDING`) を関連付ける。
   - `ITfCategoryMgr::RegisterCategory` でセクション 3.2 の全カテゴリを登録する。
   - `ActivateProfile` および `LoadKeyboardLayoutW` で現在のセッションに有効化する。

### 5.2 アンインストールフロー (`uninstall`)
- `UnregisterProfile` と `UnregisterCategory`（過去の旧GUIDクリーンアップ含む）を実行。
- `HKLM\Software\Classes\CLSID\{CLSID_KARUKAN_TIP}` および `HKLM\...\CTF\TIP\{CLSID_KARUKAN_TIP}`、`HKCU` 側の対応レジストリツリーを削除する。
- ロード中のプロセスを壊さないよう、DLLファイル自体はディスクに残し、サインアウト後の手動削除を案内する。

---

## 6. 設定アプリケーション設計 (`src/bin/settings.rs`)

`karukan-settings.exe` は外部GUIフレームワークに依存しないネイティブ Win32 API (`windows` クレート) 製のGUIアプリケーションである。

### 6.1 画面レイアウト
- **左ペイン（サイドバー）**: オーナードロー `LISTBOX`（幅 `200px`、項目高さ `40px`）。選択項目にアクセントバーとハイライトを描画。
- **右ペイン（コンテンツ領域）**:
  - 一般設定カテゴリでは縦スクロール可能な設定フォームパネル (`KarukanSettingsContent`) を表示し、チェックボックス、ラジオボタン、テキスト入力欄、複数行TOMLエディタを動的に配置する。
  - 下部には常時固定のステータスバーとアクションボタン（「保存」「再読み込み」等）を配置する。

### 6.2 カテゴリ別機能とデータ保存仕様

| カテゴリ | 対象データ / パス | 主要機能・設計仕様 |
| :--- | :--- | :--- |
| **一般設定カテゴリ群**<br/>(入力・変換・モデル等) | `%APPDATA%\karukan\karukan-im\config\config.toml` | ・カテゴリ切替時も編集中の入力状態をメモリ上に保持。<br/>・保存時に TOML 構文解析および `Settings` 構造体への型検証を実施。<br/>・一時ファイルへ書き出した後、`MoveFileExW(MOVEFILE_REPLACE_EXISTING \| MOVEFILE_WRITE_THROUGH)` でアトミックに置換。 |
| **学習履歴** | `%APPDATA%\karukan\karukan-im\data\learning.tsv` | ・`SysListView32` (`LVS_REPORT \| LVS_EX_FULLROWSELECT \| LVS_EX_DOUBLEBUFFER`) で「読み」「表記」「選択回数」「最終選択日時」を一覧表示。<br/>・列ヘッダークリックによる昇順/降順ソート。<br/>・Ctrl/Shift 複数選択による「選択した履歴を削除」（同一候補がより長い読みに前方一致で含まれるエントリも連動削除）および「すべて削除」。 |
| **ユーザー辞書** | `%APPDATA%\karukan\karukan-im\data\user_dicts\karukan-user.tsv` | ・「読み」「表記」「品詞」「コメント」の追加・更新・削除。<br/>・Mozc / Google 日本語入力互換の TSV フォーマットでアトミック保存。同フォルダ内の他辞書ファイルは変更しない。 |
| **ライセンス** | バイナリ埋め込み | ・Karukan のデュアルライセンス (`MIT` および `Apache-2.0`) 全文を読み取り専用エディットコントロールに表示。 |

---

## 7. ファイル・ディレクトリ構成一覧

### 7.1 ソースコード構成 (`karukan-im/windows/`)
```text
karukan-im/windows/
├── Cargo.toml                  # クレート定義・依存関係
├── build.rs                    # アイコン・マニフェスト等のWin32リソース (.rc) コンパイル
├── build-windows.ps1           # 静的CRT (/MT) リリースビルドスクリプト
├── package-windows.ps1         # 配布用ZIPパッケージ生成スクリプト
└── src/
    ├── lib.rs                  # GUID/定数定義、HuggingFaceキャッシュ初期設定
    ├── tsf/
    │   ├── mod.rs              # DllGetClassObject, ClassFactory, ロガー初期化
    │   ├── text_service.rs     # ITfTextInputProcessor (Activate / Deactivate)
    │   ├── key_handler.rs      # ITfKeyEventSink (キー変換・モード切替・エンジン呼び出し)
    │   ├── edit_session.rs     # ITfEditSession (Preedit更新・確定・周辺文脈取得)
    │   ├── candidate_window.rs # Direct2D/GDI 候補ポップアップウィンドウ
    │   ├── compartment.rs      # ITfCompartmentEventSink & Compartment 読み書き
    │   └── lang_bar.rs         # ITfLangBarItemButton (トレイアイコン・右クリックメニュー)
    └── bin/
        ├── install.rs          # インストーラー / アンインストーラー (install.exe)
        └── settings.rs         # 設定・学習履歴・ユーザー辞書GUI (karukan-settings.exe)
```

### 7.2 ランタイムデータ配置パス
| 種別 | パス | 備考 |
| :--- | :--- | :--- |
| インストール先バイナリ | `%LOCALAPPDATA%\Programs\Karukan\` | `karukan_im_windows-<ver>-<ts>.dll`, `karukan-settings.exe` |
| 設定ファイル | `%APPDATA%\karukan\karukan-im\config\config.toml` | TOML形式 |
| システム辞書 | `%APPDATA%\karukan\karukan-im\data\dict.bin` | `config.toml` の `dict_path` で変更可 |
| 学習履歴 | `%APPDATA%\karukan\karukan-im\data\learning.tsv` | 変換確定時および `Deactivate` 時に保存 |
| ユーザー辞書 | `%APPDATA%\karukan\karukan-im\data\user_dicts\karukan-user.tsv` | Mozc互換TSV形式 |
| 変換モデルキャッシュ | `%LOCALAPPDATA%\huggingface\hub\` | `HF_HUB_CACHE` 等が設定されている場合はそちらを優先 |
| TSF動作ログ | `%LOCALAPPDATA%\karukan\karukan-im\logs\windows-tsf.log` | `RUST_LOG` 環境変数でフィルタ変更可（既定: `info`） |
