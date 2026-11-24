# Karukan for Windows ビルド手順

本ドキュメントでは、Windows 11 x64 向けに Karukan IME をソースコードからビルドするための環境構築手順およびビルド方法について説明します。

---

## 前提条件（開発環境の準備）

Karukan for Windows のビルドには、Rust（rustc / cargo）および C/C++ コンパイラ・リンカー（MSVC の `cl.exe` / `link.exe`、CMake）が必要です。

### 1. Visual Studio Build Tools（MSVC リンカー `link.exe`、C++ ツールセット）

Rust の `x86_64-pc-windows-msvc` ターゲットや、依存する C/C++ ライブラリ（llama.cpp 等）のコンパイルとリンクに必要です。

1. **[Visual Studio インストーラー](https://visualstudio.microsoft.com/visual-cpp-build-tools/)** から「Build Tools for Visual Studio」をダウンロードして起動します。
   （※ Visual Studio Community / Professional が既にインストールされている場合は、既存の Visual Studio インストーラーから「変更」できます）
2. ワークロード一覧から **「C++ によるデスクトップ開発」** にチェックを入れます。
3. 右側のインストール詳細で以下のコンポーネントが含まれていることを確認し、インストールします：
   - **MSVC v143 - VS 2022 C++ x64/x86 ビルド ツール**（`link.exe`, `cl.exe` を含む）
   - **Windows 11 SDK**（または Windows 10 SDK）
   - **C++ CMake ツール (Windows 版)**（llama.cpp 等のビルドに必要）
4. インストール後、PowerShell で `link.exe` にパスが通っているか確認します。
   （パスが通っていない場合は「x64 Native Tools Command Prompt for VS」を使用するか、環境変数 `PATH` を確認してください）

> **winget を利用する場合:**
> ```powershell
> winget install Microsoft.VisualStudio.2022.BuildTools --override "--passive --config https://aka.ms/vs/17/release/vs_buildtools.json --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
> ```

### 2. Rust と Cargo のインストール

1. **[rustup.rs](https://rustup.rs/)** から `rustup-init.exe` をダウンロードして実行します。
2. インストール時の選択肢では通常 `1) Proceed with standard installation (default)` を選択します。
   （デフォルトでホストトリプル `x86_64-pc-windows-msvc` が選択され、`rustc`、`cargo`、標準ライブラリがインストールされます）
3. インストール完了後、PowerShell を新しく開き、以下を実行してバージョンが表示されることを確認します：

```powershell
rustc --version
cargo --version
```

### 3. CMake / Git

依存ライブラリのビルドで必要となるため、未インストールの場合は導入してください。

```powershell
# winget でインストールする場合
winget install Kitware.CMake
winget install Git.Git
```

---

## ビルド手順

リポジトリのルートディレクトリから、付属のビルドスクリプトを実行します。
Windows 向けの Rust および C/C++ 依存ライブラリは静的 CRT（`/MT`）でビルドされます。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\karukan-im\windows\build-windows.ps1 -Release
```

ビルド成果物の `karukan_im_windows.dll` と `install.exe` は、`target\x86_64-pc-windows-msvc\release` に生成されます。
設定アプリの `karukan-settings.exe` も同じフォルダーに生成されます。

### クリーンビルド

過去の中間生成物やキャッシュを消去してビルドし直す場合は、`cargo clean` を実行してからビルドします。

```powershell
# リリース成果物を消去してクリーンビルド
cargo clean --release
.\karukan-im\windows\build-windows.ps1 -Release

# C/C++ 依存ライブラリ（llama.cpp 等）も含めて完全に消去してビルドする場合
cargo clean
.\karukan-im\windows\build-windows.ps1 -Release
```

---

## 配布用 ZIP パッケージの作成

リポジトリルートの PowerShell から次を実行すると、リリースビルドを行い、
`install.exe`、`karukan_im_windows.dll`、`karukan-settings.exe` とインストール手順を含む ZIP を `karukan-im\windows\dist\karukan-windows-x64-v<version>.zip` に作成します。

```powershell
.\karukan-im\windows\package-windows.ps1
```

クリーンビルドから作成する場合は、`cargo clean --release` を実行した後に `package-windows.ps1` を実行してください。

### ZIP パッケージに含まれるファイル

- `install.exe`: TSF 登録・解除、COM クラス登録を行うインストーラー
- `karukan_im_windows.dll`: Karukan IME の TSF DLL 本体
- `karukan-settings.exe`: 設定 GUI アプリケーション
- `README.txt`: インストール・アンインストール手順書
- `LICENSE-MIT` / `LICENSE-APACHE`: ライセンスファイル

