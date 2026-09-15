package com.beatseeker.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 【クラスの役割】 設定値の切替日時を {@link IidxVersions} に流し込む。
 *
 * 現行作の判定（{@link IidxVersions#current()}）と世代切り替えの実行
 * （{@link VersionTransitionScheduler}）が別々の日時を見ると、
 * 「フロントは 34 なのに DB はまだ 33 のまま」のような食い違いが起きる。
 * どちらも {@code app.version-transition.launch-at} を唯一の情報源にするための橋渡し。
 *
 * 設定が無い・解釈できない場合は {@link IidxVersions} のコード既定値をそのまま使う
 * （警告ログのみ。起動は止めない）。
 */
@Component
public class VersionSwitchConfigurer {

    private static final Logger log = LoggerFactory.getLogger(VersionSwitchConfigurer.class);

    private final String launchAtRaw;

    public VersionSwitchConfigurer(@Value("${app.version-transition.launch-at:}") String launchAtRaw) {
        this.launchAtRaw = launchAtRaw;
    }

    @PostConstruct
    void configure() {
        if (launchAtRaw == null || launchAtRaw.isBlank()) {
            log.info("[世代切替] launch-at 未設定のため現行作の切替日時はコード既定値 {} JST を使う", IidxVersions.switchAt());
            return;
        }
        try {
            IidxVersions.configureSwitchAt(LocalDateTime.parse(launchAtRaw.trim()));
            log.info("[世代切替] 現行作の切替日時: {} JST（現在の現行作 = {}）", IidxVersions.switchAt(), IidxVersions.current());
        } catch (DateTimeParseException e) {
            log.warn("[世代切替] launch-at を解釈できないため切替日時はコード既定値 {} のまま: value={}", IidxVersions.switchAt(), launchAtRaw);
        }
    }
}
