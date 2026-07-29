package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 軍人将棋（伝統的な 23 枚型）の 1 対局を表す。
 *
 * 現実世界の概念: 友人同士が「盤・駒・審判」を囲んで指す 1 局。
 * 軍人将棋は互いの駒種が見えない不完全情報ゲームで、駒がぶつかった時の勝敗は
 * 第三者の「審判」が駒を見比べて宣告する。オンライン化に当たっては
 * <b>サーバがその審判役</b>を務める。したがって盤の真の状態（どのマスにどの駒種があるか）は
 * この 1 行にだけ存在し、各プレイヤーへは自分の駒だけを可視化した射影を返す。
 *
 * マッピング先テーブル: {@code gunjin_games}。
 *
 * 設計メモ:
 *  - beat-seeker アカウントに紐づけない。{@link #roomCode} を知っていれば誰でも入室でき、
 *    入室時に発行される {@link #p1Token} / {@link #p2Token} が「自分がどちらの陣営か」の唯一の証明になる。
 *    これはアカウントを持たない友人と遊ぶための意図的な設計。
 *  - 盤の状態は JSON テキスト 1 本（{@link #piecesJson}）に持たせる。
 *    マス数 46・駒数 46 の小さなゲームなので、正規化して駒テーブルを作る利点が無く、
 *    「1 手 = 1 行の楽観ロック更新」で完結する方が安全。
 *  - {@link #stateVersion} は 1 手ごとに +1 する。フロントはこの数値だけを見て
 *    「盤が動いたか」を判定できるので、ポーリングが軽くなる。
 *  - {@link Version} による楽観ロックで、同時着手による盤の壊れを防ぐ。
 */
@Entity
@Table(name = "gunjin_games", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gunjin_games_room_code", columnNames = "room_code")
}, indexes = {
        @Index(name = "idx_gunjin_games_updated_at", columnList = "updated_at")
})
@Data
@NoArgsConstructor
public class GunjinGame {

    /** 対局の進行状態。 */
    public enum Status {
        /** 後手の入室待ち。 */
        WAITING,
        /** 両者入室済み。互いに秘密裏に布陣（初期配置）を組んでいる。 */
        SETUP,
        /** 両者の布陣が完了し、交互に指している。 */
        PLAYING,
        /** 決着済み。 */
        FINISHED
    }

    /** 主キー。DB 採番。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 友人に口頭・チャットで伝えるための入室コード（例: {@code "7K2M"}）。
     * 紛らわしい文字（0/O/1/I 等）を除いた英数字で生成する。
     */
    @Column(name = "room_code", length = 8, nullable = false, updatable = false)
    private String roomCode;

    /**
     * 先手（盤の下側）の本人証明トークン。部屋を作った人に 1 度だけ返し、
     * 以降のリクエストで自分の駒を見るために提示させる。
     */
    @Column(name = "p1_token", length = 64, nullable = false, updatable = false)
    private String p1Token;

    /** 後手（盤の上側）の本人証明トークン。入室時に発行する。 */
    @Column(name = "p2_token", length = 64)
    private String p2Token;

    /** 先手の表示名（自由入力）。 */
    @Column(name = "p1_name", length = 40)
    private String p1Name;

    /** 後手の表示名（自由入力）。後手が入室するまで null。 */
    @Column(name = "p2_name", length = 40)
    private String p2Name;

    /** 進行状態。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Status status = Status.WAITING;

    /**
     * 盤上に生存している全駒の配置。{@code [{"i":1,"o":1,"t":"TAISHO","c":"r9c2"}, ...]} 形式の JSON。
     * i=駒の通し番号 / o=所有者(1|2) / t=駒種 / c=マス ID。
     *
     * SETUP 中は「布陣を提出済みのプレイヤーの駒」だけが入る（片側だけの状態もあり得る）。
     * これがゲームの唯一の真実で、API 応答では相手の {@code t} を伏せて返す。
     */
    @Column(name = "pieces_json", columnDefinition = "TEXT")
    private String piecesJson;

    /** 先手が布陣を提出済みか。 */
    @Column(name = "p1_ready", nullable = false)
    private boolean p1Ready = false;

    /** 後手が布陣を提出済みか。 */
    @Column(name = "p2_ready", nullable = false)
    private boolean p2Ready = false;

    /** 手番のプレイヤー（1=先手 / 2=後手）。PLAYING 以外では意味を持たない。 */
    @Column(nullable = false)
    private int turn = 1;

    /**
     * 棋譜（審判の宣告ログ）。{@code [{"n":1,"o":1,"from":"r7c1","to":"r6c1","r":"MOVE"}, ...]} 形式の JSON。
     *
     * 重要: ここには<b>駒種を書かない</b>。審判は「どちらが勝ったか」しか宣告しないという
     * 軍人将棋のルールをそのまま守るため、ログからも駒種が漏れないようにしている。
     */
    @Column(name = "log_json", columnDefinition = "TEXT")
    private String logJson;

    /**
     * 取り除かれた駒の墓場。{@code [{"o":1,"t":"TAII","n":12}, ...]} 形式の JSON
     * （o=所有者 / t=駒種 / n=何手目に失ったか）。
     *
     * API 応答では<b>閲覧者自身の駒だけ</b>を返す。自分がどの駒を失ったかは当然本人には分かるが、
     * 相手には分からない——という軍人将棋の情報構造をそのまま再現するため。
     */
    @Column(name = "dead_json", columnDefinition = "TEXT")
    private String deadJson;

    /** 指し手の総数。棋譜の採番と「何手目か」の表示に使う。 */
    @Column(name = "move_count", nullable = false)
    private int moveCount = 0;

    /** 勝者（1|2）。未決着は null。 */
    private Integer winner;

    /**
     * 決着理由。{@code "HQ"}=総司令部占領 / {@code "ANNIHILATED"}=動かせる駒の全滅 /
     * {@code "RESIGNED"}=投了。
     */
    @Column(name = "win_reason", length = 24)
    private String winReason;

    /**
     * 盤が変化するたびに +1 されるカウンタ。
     * フロントはポーリングでこの値の変化だけを見て再描画すればよい。
     */
    @Column(name = "state_version", nullable = false)
    private int stateVersion = 0;

    /** 部屋の作成日時。 */
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 最終更新日時。放置部屋の掃除にも使う。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** JPA 楽観ロック用。同時着手時に片方を弾いて盤の整合性を守る。 */
    @Version
    @Column(name = "lock_version")
    private Long lockVersion;
}
