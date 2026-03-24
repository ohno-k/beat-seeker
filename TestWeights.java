import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TestWeights {
    private static final Map<String, Integer> WEIGHTS = new HashMap<>();

    static {
        int weight = 145;
        for (int i = 0; i <= 20; i++) {
            double rankValue = 11.0 + i * 0.1;
            String rank = String.format(Locale.US, "%.1f", rankValue);
            WEIGHTS.put(rank, weight);
            weight += (rankValue >= 12.49) ? 3 : 2;
        }
    }

    public static void main(String[] args) {
        for (Map.Entry<String, Integer> entry : WEIGHTS.entrySet()) {
            System.out.println(entry.getKey() + " -> " + entry.getValue());
        }
        System.out.println("Weight for 13.0: " + WEIGHTS.get("13.0"));
    }
}
