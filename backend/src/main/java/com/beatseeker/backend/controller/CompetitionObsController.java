package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.repository.CompetitionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 OBS ブラウザソース向けの 公開 (認証不要) エンドポイント。
 *
 * <p>大会主催が発行した {@code obs_token} 1 つだけで個人戦の順位表が読める。
 * トークンが漏れた場合は管理画面側で再採番して旧 URL を無効化する想定。
 *
 * <p>SecurityConfig 側で {@code /api/obs/**} を {@code permitAll} に追加する必要がある。
 */
@RestController
@RequestMapping("/api/obs")
public class CompetitionObsController {

    private final CompetitionRepository competitionRepository;
    private final CompetitionIndividualAdminController individualAdminController;

    public CompetitionObsController(
            CompetitionRepository competitionRepository,
            CompetitionIndividualAdminController individualAdminController) {
        this.competitionRepository = competitionRepository;
        this.individualAdminController = individualAdminController;
    }

    /**
     * 【メソッドの役割】 OBS 公開トークン経由で個人戦の順位表だけを返す。
     *
     * <p>レスポンスは大会名と簡略化した順位表行 (displayName / prelimRank / prelimPoints /
     * first / fourth / remainingMatches) に限定し、個人情報や招待トークンは含めない。
     *
     * <p>トークン不一致や individual4 以外のフォーマットは 404 を返す
     * (token が当たったかどうかを攻撃者に推測されないため一貫した応答にする)。
     */
    @GetMapping("/individual/{token}/standings")
    public ResponseEntity<Map<String, Object>> getIndividualStandings(@PathVariable String token) {
        Competition comp = competitionRepository.findByObsToken(token).orElse(null);
        if (comp == null || !"individual4".equals(comp.getFormat())) {
            return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        }
        Map<String, Object> standings = individualAdminController.computeStandings(comp);

        // 詳細なフィールドのうち OBS 用に必要な列だけを残す
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) standings.get("rows");
        List<Map<String, Object>> trimmed = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("prelimRank", r.get("prelimRank"));
            t.put("displayName", r.get("displayName"));
            t.put("prelimPoints", r.get("prelimPoints"));
            t.put("first", r.get("first"));
            t.put("fourth", r.get("fourth"));
            t.put("remainingMatches", r.get("remainingMatches"));
            trimmed.add(t);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("competitionName", comp.getName());
        root.put("competitionStatus", comp.getStatus());
        root.put("rows", trimmed);
        return ResponseEntity.ok(root);
    }
}
