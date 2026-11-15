<div align="center">

# [MeiloX](https://github.com/NEORUAA/MeiloX)

<img src="./screenshot/logo.png" width="120px" style="border-radius:12px"/>

### Mei をベースに Apple Music なデザインを忠実に再現した<br>マルチソース対応の Android 向け音楽クライアント

![Android](https://img.shields.io/badge/Platform-Android-green?logo=android)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-GPLv3-orange)
![Status](https://img.shields.io/badge/Development-In%20Progress-yellow)

</div>

---

Language: [中文](./README.md) | **日本語**
## プロジェクトの概要

MeiloX は [Mei](https://github.com/ljyh223/Mei) をベースに改良と複数の音楽ソースに対応した音楽クライアントです。デフォルトで NetEase Cloud Music のソースが組み込まれていますが削除可能です。**Jetpack Compose** でビルドされており、iOS 27 に見られる奥行き感、Liquid Glass のような質感と没入感のある音楽体験を Android 上で実現することを目指しています。

このプロジェクトは 3 つを重視しています:

- iOS スタイルのページ構成を用いて、音楽コンテンツを再構成する。
- Liquid Glass、ダイナミックな背景、アルバムアートを活用し音楽体験をより没入感のあるものにする。
- 楽曲、プレイリスト、歌詞、プロフィールなどのこれまでの利用履歴や設定を保持。

> MeiloX は非公式かつ個人のオープンソースプロジェクトであり、NetEase Cloud Music や Apple とは一切関係ありません。<br>
> プロジェクト自体は音楽リソースを提供しておらず、音楽コンテンツおよび関連する著作権は、それぞれの権利者に帰属します。

## 機能の概要

- 特定の音源における Cookie/Mobile の完全なプロトコルによるログインと、アカウント情報の表示
- 曲、アルバム、アーティスト、プレイリストの検索
- 再生キュー、シャッフル再生、リピート再生、スリープタイマー
- 再生履歴、お気に入りの音楽、プレイリスト管理、ローカルのコレクション
- アルバムの詳細、アーティストのホーム、毎日のおすすめ、FM 再生
- 単語ごとの歌詞と、多言語歌詞の表示
- Apple Music スタイルのフルスクリーンプレーヤー、ミニプレーヤー、歌詞のフローティング表示
- ライト/ダークテーマ、動的なアルバム背景、Liquid Glass のインタラクション効果
- デフォルトでインストールされ削除も可能な NetEase 音源、独立した QQ Music プラグイン、ユーザーが指定した順序による再生音源の自動切り替え
- [プラグインマーケット](https://github.com/NEORUAA/MeiloX-Source-Registry)、起動時のバージョンチェックと自動更新。開発者は PR を提出して掲載できます

機能は継続的に調整中であり、実際に利用できる範囲はバージョンや音楽ソースのインターフェースの状態によって変わります。

音源の導入、設定、切り替えについては[音源の使い方](docs/music-sources.md)をご覧ください。開発者は[プラグイン仕様と SDK](docs/source-plugin-spec.md)および[アーキテクチャと互換性移行](docs/multi-source-architecture.md)も参照できます。

## 歌詞の共有

[Lyricon Provider](https://tomakino.github.io/lyricon/zh-cn/developer/provider/)、[SuperLyricApi](https://github.com/HChenX/SuperLyricApi)、[ColorOS Live Lyrics Bridge](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge) のインターフェースに対応しており、歌詞、単語ごとのタイミング、翻訳、再生状態を共有できます。

「設定 → 歌詞設定 → 歌詞の共有」にある「歌詞の共有を有効化」は、デフォルトでオンになっています。

## オープンソースへの謝辞

MeiloX のインターフェース、歌詞、基盤機能は、以下のプロジェクトにインスピレーションを受けるか、そのオープンソースコンポーネントを直接使用しています。すべての貢献者に感謝します。

- [Mei](https://github.com/ljyh223/Mei): 上流の Android プロジェクトと基盤機能
- [MeloX](https://github.com/youshen2/MeloX): iOS 版のリファレンス実装とプロダクトの方向性
- [Glasmio](https://github.com/NEORUAA/Glasmio): 高品質な iOS アニメーションとインターフェースライブラリ
- [amll-ttml-db](https://github.com/Steve-xmh/amll-ttml-db): 高品質な単語ごとの歌詞データ
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui): 歌詞モデルとレンダリングコンポーネント
- [Lyricon](https://github.com/tomakino/lyricon): 歌詞表示プロバイダー SDK
- [SuperLyricApi](https://github.com/HChenX/SuperLyricApi): 歌詞の共有 SDK
- [ColorOS Live Lyrics Bridge](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge): ColorOS ロック画面歌詞インターフェース

## ライセンスとサードパーティ声明

MeiloX の本体コードは [GNU General Public License v3](LICENSE) の下で公開されています。MeiloX は Mei の派生プロジェクトであり、関連するアップストリームコードとリファレンス実装は、引き続き元の著作権とライセンスの制約を受けます。サードパーティのライブラリ、モデル、スクリプト、フォント、ビジュアル素材は、MeiloX が GPLv3 を使用しているからといって、自動的に GPLv3 になるわけではありません。

主な声明は以下のとおりです。

- [Mei](https://github.com/ljyh223/Mei) の上流コードは Apache License 2.0 に従います
- [MeloX](https://github.com/youshen2/MeloX) の関連リファレンスコードは、同プロジェクトの GPLv3 条項に従います
- [Glasmio](https://github.com/NEORUAA/Glasmio) は Apache License 2.0 に従い、保持しているサードパーティコードの出典と帰属は [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) に記載されています
- [amll-ttml-db](https://github.com/Steve-xmh/amll-ttml-db) と [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui) は、それぞれのリポジトリで公表されているライセンスと帰属要件に従います
- BeatNet モデル、音声フィンガープリントランタイム、ONNX Runtime、PV Tool の派生コンテンツ、その他プロジェクトと共に配布されるコンポーネントは、それぞれのライセンス、著作権表示、再配布制限に従います

完全な出典、著作権の帰属、ライセンスへのリンク、特別な再配布要件については [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) をお読みください。なお `res/font/sf_pro.ttf` は個人プロジェクトのリソースとしてのみ使用しています。ビルド成果物から単独で抽出したり、再配布したりしないでください。ビルド版を公開する前に、Apple のフォントライセンス条項を再確認してください。

## 画面プレビュー

以下のプレビューは [`screenshot/2026-08-22`](./screenshot/2026-08-22/) のもので、ライトモード、ダークモード、プレーヤーの画面を表示しています。

### ライトモード

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-09-47-44-801_com.neoruaa.meilox.jpg" width="200" alt="Light mode 01"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-31-11-383_com.neoruaa.meilox.jpg" width="200" alt="Light mode 07"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-06-44-461_com.neoruaa.meilox.jpg" width="200" alt="Light mode 04"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-05-43-651_com.neoruaa.meilox.jpg" width="200" alt="Light mode 02"></td>
  </tr>
  <tr>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-13-55-779_com.neoruaa.meilox.jpg" width="200" alt="Light mode 05"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-14-22-986_com.neoruaa.meilox.jpg" width="200" alt="Light mode 06"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-06-04-617_com.neoruaa.meilox.jpg" width="200" alt="Light mode 03"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-33-30-925_com.neoruaa.meilox.jpg" width="200" alt="Light mode 08"></td>
  </tr>
</table>

### ダークモード

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-19-250_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 02"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-28-56-966_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 07"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-19-07-140_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 06"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-04-531_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 01"></td>
  </tr>
  <tr>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-59-681_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 04"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-34-42-884_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 08"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-30-088_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 03"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-18-09-075_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 05"></td>
  </tr>
</table>

### プレーヤーと歌詞

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-12-05-012_com.neoruaa.meilox.jpg" width="200" alt="Player 02"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-11-20-597_com.neoruaa.meilox.jpg" width="200" alt="Player 01"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-16-34-746_com.neoruaa.meilox.jpg" width="200" alt="Player 03"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-16-52-289_com.neoruaa.meilox.jpg" width="200" alt="Player 04"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-21-10-403_com.neoruaa.meilox.jpg" width="200" alt="Player 05"></td>
  </tr>
</table>

## ソースからビルド

JDK 21 と Android SDK 37 を使用します。デフォルトでは、同じ階層にある `../Glasmio` から最新のソースをビルドします。

```sh
git clone https://github.com/NEORUAA/Glasmio.git ../Glasmio
./gradlew :app:assembleDebug
```

最新リリース済みの Maven パッケージを使用する場合は、ソースの置き換えを明示的に無効化できます。

```sh
./gradlew -Pglasmio.source=false -Pglasmio.version=0.1.3 :app:assembleDebug
```

Glasmio のレンダラー、ナビゲーションビューおよびミニプレーヤーのネイティブアタッチメントアニメーションに関しては [Glasmio のドキュメント](docs/glasmio-integration.md) を参照してください。

## 免責事項


本プロジェクトは、学習と研究および個人的利用を目的としています。本プロジェクトを使用して、NetEase Cloud Music のサービスにアクセスする際は、現地の法令と当該音楽サービスの利用規約、関連する著作権要件を遵守してください。プロジェクトの管理者は、サードパーティーサービスの利用とアカウントの状態および音楽コンテンツの利用に関しての一切の責任を負いません。

権利侵害のコンテンツ、ライセンスの記載漏れ、不正確な第三者に関する記述を発見した場合は、[Issues](https://github.com/NEORUAA/MeiloX/issues) からメンテナにご連絡ください。

<div align="center">

**Made with Jetpack Compose ♥ MeiloX**

</div>
