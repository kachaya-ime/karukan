# Karukan for Windows

このクレートは、共有Rust IMEエンジンを利用するWindows 11 x64向け
Text Services Framework（TSF）フロントエンドです。

## ビルド

リポジトリのルートディレクトリから、次のスクリプトを実行します。
Windows向けのRustおよびC/C++依存ライブラリは静的CRT（`/MT`）でビルドします。

```powershell
.\karukan-im\windows\build-windows.ps1 -Release
```

ビルド成果物の`karukan_im_windows.dll`と`install.exe`は、
`target\x86_64-pc-windows-msvc\release`に生成されます。
設定アプリの`karukan-settings.exe`も同じフォルダーに生成されます。

## 配布用ZIP

リポジトリルートのPowerShellから次を実行すると、リリースビルドを行い、
`install.exe`、`karukan_im_windows.dll`、`karukan-settings.exe`とインストール手順を
含むZIPを`karukan-im\windows\dist\karukan-windows-x64-v<version>.zip`に作成します。

```powershell
.\karukan-im\windows\package-windows.ps1
```

GitHub ReleaseにはこのZIPを添付してください。インストーラーは実行時にGitHub Releasesの
`dict.tgz`とHugging Faceのモデルをダウンロードするため、配布先のWindows PCは
インストール時にインターネットへ接続できる必要があります。ZIPにはMITおよびApache-2.0の
ライセンスファイルを含み、設定アプリの「ライセンス」カテゴリでもライセンス本文を確認できます。

## インストール

インストーラーはCOMクラスをWindowsのシステム領域に登録するため、
PowerShellを「管理者として実行」してから、次のコマンドを実行してください。
IMEの言語プロファイルは現在のユーザーに登録されます。

```powershell
.\target\x86_64-pc-windows-msvc\release\install.exe install
```

続いてWindowsの「設定」から「時刻と言語」>「言語と地域」>「日本語」
>「言語のオプション」>「キーボード」で、**Karukan Japanese Input**を
追加して選択してください。DLLは`%LOCALAPPDATA%\Programs\Karukan`に
バージョンごとの別ファイル名でコピーされます。更新時に旧DLLが使用中でも
上書きしないため、そのまま再インストールできます。古いDLLはサインアウト後に
同フォルダーから削除できます。

日本語入力と直接入力（英字）の切り替えは、Windows標準の半角/全角キーで行います。
JISキーボードの「変換」キーはfcitx5版と同様に、カタカナ／英数字モードから
ひらがなモードへ戻す操作に使えます。

インストール時に、GitHub Releasesの`dict.tgz`を一時領域へダウンロード・展開し、
含まれる`dict.bin`を配置します。通常の配置先は
`%APPDATA%\karukan\karukan-im\data\dict.bin`です。設定の
`[conversion] dict_path`が指定されていればそのパスを使います。
インストーラーは実際の保存先を表示し、辞書を配置できない場合はエラーで終了します。
既に辞書がある場合は保持します。設定中の変換モデル
（メインモデルと、adaptive設定時の軽量モデル）および各モデルの`tokenizer.json`も
Hugging Faceキャッシュへ事前取得し、それぞれの保存先を表示します。モデルまたは
トークナイザーを取得できない場合もエラーで終了します。ダウンロードには
ネットワーク接続が必要です。キャッシュの既定場所は
`%LOCALAPPDATA%\huggingface\hub`です（`HF_HUB_CACHE`、`HF_HOME`などを
設定している場合はその設定が優先されます）。

初回インストール時に、デフォルト設定が
`%APPDATA%\karukan\karukan-im\config\config.toml`へ作成されます。
既に設定ファイルがある場合は上書きしません。設定を変更した後は、
サインアウトして再度サインインすると反映されます。

設定アプリは`karukan-settings.exe`を起動します。「学習履歴」以外のカテゴリでは、
設定項目とチェックボックス、ラジオボタン、入力欄を同じ画面に並べて編集できます。
設定項目の領域は縦スクロールに対応し、画面下の状態表示と操作ボタンは固定されます。
日付・時刻やモデルなど配列・テーブル形式の値はTOML形式で編集できます。
カテゴリを切り替えると入力内容を保持し、「保存」で設定ファイルへ反映します。
保存時に設定の構文と型を検証してから置き換えます。初期設定ファイルはインストーラーが作成します。
IMEに反映するにはサインアウトして再度サインインしてください。インストール時に
`%LOCALAPPDATA%\Programs\Karukan\karukan-settings.exe`にも配置されます。

設定アプリの「学習履歴」では、読み・表記・選択回数・最終選択日時を列ごとの一覧で確認できます。
列見出しをクリックするとその列で並べ替え、同じ見出しを再度クリックすると昇順・降順が切り替わります。
CtrlキーまたはShiftキーを押しながら行を選択すると複数選択でき、選択した履歴をまとめて削除できます。
選択した履歴の削除は同じ候補がより長い読みで再表示される分も削除します。
「すべて削除」は学習履歴全体を消去し、どちらも確認ダイアログの後に保存します。
学習履歴の保存先は`%APPDATA%\karukan\karukan-im\data\learning.tsv`です。
変換候補を確定すると学習内容を保存し、初回の学習後にこのファイルが作成されます。
メンテナンス時は、確認ダイアログの案内に従ってIMEを使用中のアプリを閉じてください。

「ユーザー辞書」カテゴリでは、読み・表記・品詞・コメントを一覧から追加・更新・削除できます。
「辞書を保存」で`%APPDATA%\karukan\karukan-im\data\user_dicts\karukan-user.tsv`へ保存します。
このファイルはMozc/Google IME互換のTSV形式です。既存のユーザー辞書ファイルは変更しません。
保存した単語を変換に反映するにはIMEを再起動してください。

「ライセンス」カテゴリではKarukanのMIT LicenseおよびApache License 2.0の本文を確認できます。
Karukanは`MIT OR Apache-2.0`のデュアルライセンスです。

TSFプロファイルとCOM登録を解除するには、管理者PowerShellから次を実行します。

```powershell
.\target\x86_64-pc-windows-msvc\release\install.exe uninstall
```

DLLを置き換える前に、サインアウトするか、IMEを使用中のアプリを終了してください。
アンインストーラーは、WindowsでDLLが読み込まれなくなった後に削除できるよう、
DLLを残します。

## 現在の対応状況

共有エンジンへの接続、キー入力、TSFの未確定文字列と確定処理に対応しています。
候補一覧は、入力位置の近くに表示する非アクティブのリストウィンドウに表示し、
選択中の候補をハイライトします。

## 謝辞

本Windows版フロントエンドの基盤となるKarukanを開発・公開されたtogatoga氏に感謝します。
