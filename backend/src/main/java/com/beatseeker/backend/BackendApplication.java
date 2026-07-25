package com.beatseeker.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 【クラスの役割】 beat-seeker バックエンドアプリケーションのエントリポイント。
 *
 * Spring Boot の起動クラスであり、{@code main} メソッドから
 * {@link SpringApplication#run} が呼ばれることで組み込み Tomcat が立ち上がり、
 * REST API サーバーとして稼働を開始する。
 *
 * 付与アノテーション:
 *  - {@link SpringBootApplication} … 自動設定 / コンポーネントスキャン / 設定クラス宣言を一括有効化
 *  - {@link EnableAsync}            … {@code @Async} アノテーションによる非同期メソッド呼び出しを許可
 *                                     （重いスコア集計処理などをバックグラウンドで走らせるために使う）
 *
 * {@code @EnableScheduling} は {@code config/SchedulingConfig} に移動した
 * （本番 DB へローカルから接続する際に定期ジョブを一括無効化できるようにするため。
 *  {@code app.scheduling.enabled=false} で全 {@code @Scheduled} が止まる）。
 *
 * コンポーネントスキャン起点:
 *  - このクラスが配置された {@code com.beatseeker.backend} パッケージ配下が対象になる。
 */
@SpringBootApplication
@EnableAsync
public class BackendApplication {

	/**
	 * 【メソッドの役割】 JVM 起動時に最初に呼ばれる標準的な main メソッド。
	 *
	 * Spring Boot アプリケーションコンテキストを構築し、組み込みサーバーを起動する。
	 *
	 * @param args コマンドライン引数（Spring のプロファイル指定などに利用可能）
	 */
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
