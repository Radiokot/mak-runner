import com.distributedlab.mak.Config;
import com.distributedlab.mak.Refunder;
import com.distributedlab.mak.Runner;
import org.tokend.sdk.utils.BigDecimalUtil;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class Main {
    private static final String FORM_URL_ENV = "EDITABLE_FORM_URL";
    private static final String SHEET_URL_ENV = "EDITABLE_SHEET_URL";
    private static final String RECEIVER_PASSWORD_ENV = "ASSET_RECEIVER_PASSWORD";
    private static final String HELPER_URL_ENV = "HELPER_URL";
    private static final String START_DATE_ENV = "START_DATE";
    private static final String IGNORED_MAC_IDS_ENV = "IGNORED_MC_IDS";
    private static final String AMOUNT_MULTIPLIER = "AMOUNT_MULTIPLIER";

    private static final String REFUND_COMMAND = "refund";

    public static void main(String[] args) {
        if (args.length >= 1) {
            String command = args[0];

            if (command.equals(REFUND_COMMAND)) {
                startRefunder();
                return;
            }
        }

        startRunner();
    }

    private static void startRunner() {
        String assetReceiverPassword = System.getenv(RECEIVER_PASSWORD_ENV);
        if (assetReceiverPassword == null || assetReceiverPassword.isEmpty()) {
            throw new IllegalArgumentException(RECEIVER_PASSWORD_ENV + " env must be set");
        }

        String helperUrl = System.getenv(HELPER_URL_ENV);
        if (helperUrl == null || helperUrl.isEmpty()) {
            throw new IllegalArgumentException(HELPER_URL_ENV + " env must be set");
        }

        String editableFormUrl = System.getenv(FORM_URL_ENV);
        if (editableFormUrl == null || editableFormUrl.isEmpty()) {
            throw new IllegalArgumentException(FORM_URL_ENV + " env must be set");
        }

        String editableSheetUrl = System.getenv(SHEET_URL_ENV);
        if (editableSheetUrl == null || editableSheetUrl.isEmpty()) {
            throw new IllegalArgumentException(SHEET_URL_ENV + " env must be set");
        }

        Date startDate = new Date();

        try {
            String startDateString = System.getenv(START_DATE_ENV);
            startDate = new Date(Long.parseLong(startDateString) * 1000L);
        } catch (Exception e) {
            // Ignore
        }

        new Runner(
                Config.ASSET_CODE,
                Config.ASSET_RECEIVER_EMAIL,
                assetReceiverPassword.toCharArray(),
                BigDecimal.ONE,
                editableFormUrl,
                editableSheetUrl,
                Config.TOKEND_API_URL,
                helperUrl
        ).start(startDate);
    }

    private static void startRefunder() {
        String assetReceiverPassword = System.getenv(RECEIVER_PASSWORD_ENV);
        if (assetReceiverPassword == null || assetReceiverPassword.isEmpty()) {
            throw new IllegalArgumentException(RECEIVER_PASSWORD_ENV + " env must be set");
        }

        String startDateString = System.getenv(START_DATE_ENV);
        if (startDateString == null || startDateString.isEmpty()) {
            throw new IllegalArgumentException(START_DATE_ENV + " env must be set for refund");
        }

        Date startDate = new Date(Long.parseLong(startDateString) * 1000L);

        String ignoredMcIdsString = System.getenv(IGNORED_MAC_IDS_ENV);
        Set<String> ignoredMcIds = new HashSet<>();
        if (ignoredMcIdsString != null && !ignoredMcIdsString.isEmpty()) {
            String[] splitMcIdsString = ignoredMcIdsString.split(",\\s?");
            ignoredMcIds.addAll(Arrays.asList(splitMcIdsString));
        }

        String amountMultiplierString = System.getenv(AMOUNT_MULTIPLIER);
        BigDecimal amountMultiplier = BigDecimalUtil.INSTANCE.valueOf(
                amountMultiplierString,
                BigDecimal.ONE
        );

        new Refunder(
                Config.ASSET_CODE,
                Config.ASSET_RECEIVER_EMAIL,
                assetReceiverPassword.toCharArray(),
                Config.TOKEND_API_URL
        )
                .start(startDate, ignoredMcIds, amountMultiplier);
    }
}
