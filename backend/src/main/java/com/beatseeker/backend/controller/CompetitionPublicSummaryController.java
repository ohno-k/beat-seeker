package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.repository.CompetitionRepository;
import com.beatseeker.backend.service.CompetitionTeamSummaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【クラスの役割】 大会サマリー (試合別 + 選手別の全結果一覧) の 公開 (認証不要) エンドポイント。team5 専用。
 *
 * <p>サマリーは大会後の振り返り用ページなので、主催・参加者に限らず誰でも読めるようにする。
 * SecurityConfig 側で {@code /api/competition-access/**} が {@code permitAll} のため、認証材料は無く
 * 大会 ID だけで引ける (観戦 URL と違いトークンも要らない)。
 *
 * <p>誰でも読める代わりに、観戦 URL ({@link CompetitionSpectatorController}) が守っている
 * staged reveal をここでも維持する。マスクの実体は
 * {@link CompetitionTeamSummaryService#compute(Competition, boolean)} の {@code publicView=true} 側:
 * <ul>
 *   <li>運営が「設定済み」にしていない matchup は返さない</li>
 *   <li>起用 (選手名) は、その matchup の起用公開日時を過ぎているときだけ返す</li>
 *   <li>結果 (スコア・勝敗) は記録済みのときだけ返す</li>
 * </ul>
 * 招待トークン / TL トークン / 観戦トークンは一切含めない。
 *
 * <p>運営が同じページを開いた場合は、フロント側が主催 API
 * ({@code GET /api/competitions/{id}/summary}) を先に試すため、マスク無しの表示になる。
 *
 * <p>大会が存在しない場合と team5 以外のフォーマットは、どちらも 404 を返す
 * (どの ID が実在するかを問い合わせで区別できないようにする)。
 */
@RestController
@RequestMapping("/api/competition-access/summary")
public class CompetitionPublicSummaryController {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamSummaryService teamSummaryService;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public CompetitionPublicSummaryController(CompetitionRepository competitionRepository,
                                              CompetitionTeamSummaryService teamSummaryService) {
        this.competitionRepository = competitionRepository;
        this.teamSummaryService = teamSummaryService;
    }

    /**
     * 【メソッドの役割】 大会 ID を指定してサマリーの読み取り専用ビューを返す (認証不要)。
     */
    @GetMapping("/{competitionId}")
    public ResponseEntity<Map<String, Object>> getPublicSummary(@PathVariable Long competitionId) {
        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        if (comp == null || !"team5".equals(comp.getFormat())) {
            return ResponseEntity.status(404).body(Map.of("message", "大会が見つかりません"));
        }
        return ResponseEntity.ok(teamSummaryService.compute(comp, true));
    }
}
