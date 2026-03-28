package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class GameDataService {

    private final SongDefinitionRepository songDefRepo;
    private final DifficultyRankRepository diffRankRepo;
    private final ScoreRecalculationService recalcService;
    private final ObjectMapper objectMapper;

    public GameDataService(SongDefinitionRepository songDefRepo,
                           DifficultyRankRepository diffRankRepo,
                           ScoreRecalculationService recalcService,
                           ObjectMapper objectMapper) {
        this.songDefRepo = songDefRepo;
        this.diffRankRepo = diffRankRepo;
        this.recalcService = recalcService;
        this.objectMapper = objectMapper;
    }

    // ── Active data (public) ──────────────────────────────────

    /** Build song_data JSON from active DB records */
    public String getActiveSongDataJson() throws Exception {
        List<SongDefinition> songs = songDefRepo.findByRevision("active");
        return buildSongDataJson(songs);
    }

    /** Build difficulty_table JSON from active DB records */
    public String getActiveDifficultyTableJson() throws Exception {
        List<DifficultyRank> ranks = diffRankRepo.findByRevisionOrderBySortOrderAsc("active");
        return buildDifficultyTableJson(ranks);
    }

    // ── Draft songs (admin) ──────────────────────────────────

    /** Return draft song definitions as a list */
    public List<SongDefinition> getDraftSongs() {
        return songDefRepo.findByRevision("draft");
    }

    /** Add a new song as draft. Creates one SongDefinition per non-null difficulty. */
    @Transactional
    public List<SongDefinition> addDraftSong(Map<String, Object> songForm) {
        String title = (String) songForm.get("title");
        String artist = (String) songForm.get("artist");
        String genre = (String) songForm.get("genre");
        String bpm = (String) songForm.get("bpm");

        List<SongDefinition> created = new ArrayList<>();

        // Each difficulty: { code, notesKey, levelKey }
        String[][] diffs = {
            {"1", "beginnerNotes", "beginnerLevel"},
            {"2", "normalNotes", "normalLevel"},
            {"3", "hyperNotes", "hyperLevel"},
            {"4", "anotherNotes", "anotherLevel"},
            {"10", "leggendariaNotes", "leggendariaLevel"},
        };

        for (String[] d : diffs) {
            String code = d[0];
            Integer notes = toInteger(songForm.get(d[1]));
            Integer level = toInteger(songForm.get(d[2]));
            if (notes == null || notes <= 0) continue;

            SongDefinition sd = new SongDefinition();
            sd.setTitle(title);
            sd.setArtist(artist);
            sd.setGenre(genre);
            sd.setBpm(bpm);
            sd.setDifficulty(code);
            sd.setNotes(notes);
            sd.setLevel(level);
            sd.setRevision("draft");

            // Extra fields for ANOTHER (4) and LEGGENDARIA (10)
            if ("4".equals(code) || "10".equals(code)) {
                sd.setWr(toInteger(songForm.get("wr")));
                sd.setAvg(toInteger(songForm.get("avg")));
                sd.setCoef(toDouble(songForm.get("coef")));
                sd.setTextage((String) songForm.get("textage"));
                sd.setDifficultyLevel(level != null ? String.valueOf(level) : null);
                sd.setDpLevel(songForm.get("dpLevel") != null ? songForm.get("dpLevel").toString() : "0");
            }

            created.add(songDefRepo.save(sd));
        }

        return created;
    }

    /** Delete a draft song definition by ID */
    @Transactional
    public void deleteDraftSong(Long id) {
        SongDefinition sd = songDefRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Song not found: " + id));
        if (!"draft".equals(sd.getRevision())) {
            throw new RuntimeException("Can only delete draft songs");
        }
        songDefRepo.delete(sd);
    }

    // ── Draft difficulty table (admin) ────────────────────────

    /** Return draft difficulty table JSON (or active if no draft exists) */
    public String getDraftDifficultyTableJson() throws Exception {
        List<DifficultyRank> draftRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        if (draftRanks.isEmpty()) {
            return getActiveDifficultyTableJson();
        }
        return buildDifficultyTableJson(draftRanks);
    }

    /** Save draft difficulty table from JSON string */
    @Transactional
    public void saveDraftDifficultyTable(String json) throws Exception {
        // Delete existing draft ranks (use deleteAll to trigger cascade on child songs)
        List<DifficultyRank> existingDraft = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        diffRankRepo.deleteAll(existingDraft);
        diffRankRepo.flush();

        // Parse and create new draft records
        JsonNode root = objectMapper.readTree(json);
        JsonNode ranksNode = root.path("ranks");
        if (!ranksNode.isArray()) {
            throw new RuntimeException("Invalid difficulty table JSON: 'ranks' array expected");
        }

        int sortOrder = 0;
        for (JsonNode rn : ranksNode) {
            DifficultyRank rank = new DifficultyRank();
            rank.setRankValue(rn.path("rank").asText());
            rank.setSortOrder(sortOrder++);
            rank.setRevision("draft");

            List<DifficultyRankSong> songList = new ArrayList<>();
            JsonNode songsNode = rn.path("songs");
            if (songsNode.isArray()) {
                int songOrder = 0;
                for (JsonNode sn : songsNode) {
                    DifficultyRankSong drs = new DifficultyRankSong();
                    drs.setDifficultyRank(rank);
                    drs.setSongTitle(sn.asText().trim());
                    drs.setSortOrder(songOrder++);
                    songList.add(drs);
                }
            }
            rank.setSongs(songList);
            diffRankRepo.save(rank);
        }
    }

    // ── Apply draft (admin) ──────────────────────────────────

    /** Promote draft data to active, then trigger recalculation */
    @Transactional
    public void applyDraft() throws Exception {
        // 1. Merge draft songs into active
        List<SongDefinition> draftSongs = songDefRepo.findByRevision("draft");
        for (SongDefinition ds : draftSongs) {
            ds.setRevision("active");
            songDefRepo.save(ds);
        }

        // 2. If draft difficulty table exists, replace active with draft
        List<DifficultyRank> draftRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("draft");
        if (!draftRanks.isEmpty()) {
            List<DifficultyRank> activeRanks = diffRankRepo.findByRevisionOrderBySortOrderAsc("active");
            diffRankRepo.deleteAll(activeRanks);
            diffRankRepo.flush();
            for (DifficultyRank dr : draftRanks) {
                dr.setRevision("active");
                diffRankRepo.save(dr);
            }
        }

        // 3. Trigger recalculation with updated data
        String songDataJson = getActiveSongDataJson();
        String diffTableJson = getActiveDifficultyTableJson();
        recalcService.recalculateAllUsersAsync(songDataJson, diffTableJson);
    }

    // ── Seed from JSON files (startup) ──────────────────────

    @Transactional
    public void seedSongData(String json) throws Exception {
        if (songDefRepo.countByRevision("active") > 0) return; // already seeded

        JsonNode root = objectMapper.readTree(json);
        JsonNode body = root.path("body");
        if (!body.isArray()) return;

        List<SongDefinition> batch = new ArrayList<>();
        for (JsonNode s : body) {
            SongDefinition sd = new SongDefinition();
            sd.setTitle(s.path("title").asText());
            sd.setArtist(s.path("artist").asText(null));
            sd.setGenre(s.path("genre").asText(null));
            sd.setNotes(s.path("notes").asInt(0));
            sd.setBpm(s.path("bpm").asText(null));
            sd.setDifficulty(s.path("difficulty").asText());
            sd.setLevel(s.path("level").asInt(0));
            if (s.has("wr")) sd.setWr(s.path("wr").asInt());
            if (s.has("avg")) sd.setAvg(s.path("avg").asInt());
            if (s.has("textage")) sd.setTextage(s.path("textage").asText());
            if (s.has("coef")) sd.setCoef(s.path("coef").asDouble());
            if (s.has("difficultyLevel")) sd.setDifficultyLevel(s.path("difficultyLevel").asText());
            if (s.has("dpLevel")) sd.setDpLevel(s.path("dpLevel").asText());
            sd.setRevision("active");
            batch.add(sd);
        }
        songDefRepo.saveAll(batch);
    }

    @Transactional
    public void seedDifficultyTable(String json) throws Exception {
        if (diffRankRepo.countByRevision("active") > 0) return; // already seeded

        JsonNode root = objectMapper.readTree(json);
        JsonNode ranksNode = root.path("ranks");
        if (!ranksNode.isArray()) return;

        int sortOrder = 0;
        for (JsonNode rn : ranksNode) {
            DifficultyRank rank = new DifficultyRank();
            rank.setRankValue(rn.path("rank").asText());
            rank.setSortOrder(sortOrder++);
            rank.setRevision("active");

            List<DifficultyRankSong> songList = new ArrayList<>();
            JsonNode songsNode = rn.path("songs");
            if (songsNode.isArray()) {
                int songOrder = 0;
                for (JsonNode sn : songsNode) {
                    DifficultyRankSong drs = new DifficultyRankSong();
                    drs.setDifficultyRank(rank);
                    drs.setSongTitle(sn.asText().trim());
                    drs.setSortOrder(songOrder++);
                    songList.add(drs);
                }
            }
            rank.setSongs(songList);
            diffRankRepo.save(rank);
        }
    }

    // ── Has draft check ──────────────────────────────────────

    public boolean hasDraftSongs() {
        return songDefRepo.countByRevision("draft") > 0;
    }

    public boolean hasDraftDifficultyTable() {
        return diffRankRepo.countByRevision("draft") > 0;
    }

    // ── Private helpers ──────────────────────────────────────

    private String buildSongDataJson(List<SongDefinition> songs) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 20241114);
        root.put("requireVersion", "87");

        ArrayNode body = objectMapper.createArrayNode();
        for (SongDefinition sd : songs) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("title", sd.getTitle());
            node.put("artist", sd.getArtist());
            node.put("genre", sd.getGenre());
            node.put("notes", sd.getNotes());
            node.put("bpm", sd.getBpm());
            node.put("difficulty", sd.getDifficulty());
            node.put("level", sd.getLevel());
            if (sd.getWr() != null) node.put("wr", sd.getWr());
            if (sd.getAvg() != null) node.put("avg", sd.getAvg());
            if (sd.getTextage() != null) node.put("textage", sd.getTextage());
            if (sd.getCoef() != null) node.put("coef", sd.getCoef());
            if (sd.getDifficultyLevel() != null) node.put("difficultyLevel", sd.getDifficultyLevel());
            if (sd.getDpLevel() != null) node.put("dpLevel", sd.getDpLevel());
            body.add(node);
        }
        root.set("body", body);
        return objectMapper.writeValueAsString(root);
    }

    private String buildDifficultyTableJson(List<DifficultyRank> ranks) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode ranksArr = objectMapper.createArrayNode();

        for (DifficultyRank dr : ranks) {
            ObjectNode rn = objectMapper.createObjectNode();
            rn.put("rank", dr.getRankValue());
            ArrayNode songsArr = objectMapper.createArrayNode();
            for (DifficultyRankSong drs : dr.getSongs()) {
                songsArr.add(drs.getSongTitle());
            }
            rn.set("songs", songsArr);
            ranksArr.add(rn);
        }

        root.set("ranks", ranksArr);
        return objectMapper.writeValueAsString(root);
    }

    private Integer toInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
