package com.phillipsouza.gfp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GfpAlertWorker extends Worker {

    private static final String PREFS_NAME = "gfp_native_prefs";

    public GfpAlertWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String rawSchedule = prefs.getString("schedule_json", "[]");
            JSONArray arr = new JSONArray(rawSchedule);
            if (arr.length() == 0) return Result.success();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String todayISO = sdf.format(new Date());
            Date todayDate = sdf.parse(todayISO);
            if (todayDate == null) return Result.success();
            long todayMs = todayDate.getTime();

            String sentKey = "sent_ids_" + todayISO;
            Set<String> alreadySent = new HashSet<>(prefs.getStringSet(sentKey, new HashSet<>()));
            List<String> triggeredLines = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String idTx = item.optString("id_tx", "tx_" + i);
                if (alreadySent.contains(idTx)) continue;

                String dataOc = item.optString("data_ocorrencia", "");
                if (dataOc.isEmpty()) continue;
                Date txDate = sdf.parse(dataOc);
                if (txDate == null) continue;

                int diffDays = (int) Math.round((txDate.getTime() - todayMs) / (1000.0 * 60 * 60 * 24));
                String status = item.optString("status", "");
                boolean isAutoDebit = item.optBoolean("isAutoDebit", false);
                JSONArray customDays = item.optJSONArray("customDays");

                boolean customMatch = false;
                if (customDays != null) {
                    for (int k = 0; k < customDays.length(); k++) {
                        if (customDays.optInt(k, -999) == diffDays) {
                            customMatch = true;
                            break;
                        }
                    }
                }

                String desc = item.optString("descricao", "Lançamento");
                if (customMatch) {
                    String prefix = diffDays == 0 ? "Vence HOJE: " : (diffDays == 1 ? "Vence AMANHÃ: " : "Faltam " + diffDays + "d: ");
                    triggeredLines.add("• " + prefix + desc);
                    alreadySent.add(idTx);
                } else if (isAutoDebit && (diffDays == 0 || diffDays == 1)) {
                    String prefix = diffDays == 0 ? "Débito Automático HOJE: " : "Débito Automático AMANHÃ: ";
                    triggeredLines.add("• " + prefix + desc);
                    alreadySent.add(idTx);
                } else if ("PENDENTE".equalsIgnoreCase(status) && (diffDays == 0 || diffDays < 0)) {
                    String prefix = diffDays == 0 ? "Vence HOJE: " : "Em atraso: ";
                    triggeredLines.add("• " + prefix + desc);
                    alreadySent.add(idTx);
                }
            }

            if (!triggeredLines.isEmpty()) {
                String title = triggeredLines.size() == 1
                        ? "🔔 GFP: 1 Alerta Financeiro Hoje"
                        : "🔔 GFP: " + triggeredLines.size() + " Alertas Financeiros Hoje";
                StringBuilder bodySb = new StringBuilder();
                for (int i = 0; i < Math.min(4, triggeredLines.size()); i++) {
                    if (i > 0) bodySb.append("\n");
                    bodySb.append(triggeredLines.get(i));
                }
                MainActivity.dispatchNativeNotification(ctx, title, bodySb.toString());
                prefs.edit().putStringSet(sentKey, alreadySent).apply();
            }
        } catch (Exception ignored) {}
        return Result.success();
    }
}

