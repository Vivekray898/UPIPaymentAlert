// ============================================================================
// Notification Listener Service — UPI Payment Alert
// Intercepts push notifications posted by payment apps (GPay, PhonePe, Paytm).
// Extracts payment amounts and delegates them to the TTS background engine.
// ============================================================================
package com.vivekray898.upipaymentalert;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.vivekray898.upipaymentalert.PaymentEvent;
import com.vivekray898.upipaymentalert.PaymentHistoryStore;
import com.vivekray898.upipaymentalert.remote.RemoteAnnouncer;
import com.vivekray898.upipaymentalert.smsparser.SmsParser;

public class NotificationListener extends NotificationListenerService {

    private final SmsParser smsParser = new SmsParser();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        
        // Filter for known UPI apps
        boolean isUpiApp = packageName.contains("com.phonepe.app") ||
                           packageName.contains("com.google.android.apps.nbu.paisa.user") ||
                           packageName.contains("net.one97.paytm") ||
                           packageName.contains("in.amazon.mShop.android.shopping") ||
                           packageName.contains("com.sbi.upi.intent") ||
                           packageName.contains("in.org.npci.upiapp") ||
                           packageName.contains("com.dreamplug.androidapp"); // Cred

        if (!isUpiApp) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");

        String messageBody = title + " " + text;
        
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("UPI_PREFS", Context.MODE_PRIVATE);

        // Forwarding Logic
        String forwardNumber = prefs.getString("forwarder_number", "");
        if (!forwardNumber.isEmpty()) {
            String appFilter = prefs.getString("forwarder_app_filter", "all");
            if ("all".equals(appFilter) || packageName.equals(appFilter)) {
                String typeFilter = prefs.getString("forwarder_type_filter", "Credited");
                boolean shouldForward = false;
                if ("Credited".equals(typeFilter) && smsParser.isCreditTransaction(messageBody)) {
                    shouldForward = true;
                } else if ("Debited".equals(typeFilter) && smsParser.isDebitTransaction(messageBody)) {
                    shouldForward = true;
                } else if ("Both".equals(typeFilter) && (smsParser.isCreditTransaction(messageBody) || smsParser.isDebitTransaction(messageBody))) {
                    shouldForward = true;
                }

                if (shouldForward) {
                    try {
                        android.telephony.SmsManager.getDefault().sendTextMessage(forwardNumber, null, "FWD NOTIF: " + messageBody, null, null);
                    } catch (Exception ignored) {}
                }
            }
        }

        // Check if it is a credit transaction for TTS announcement
        if (!smsParser.isCreditTransaction(messageBody)) {
            return;
        }
        String lang = prefs.getString("language", "English");
        String textToRead = smsParser.getAmountFromMessageBody(messageBody, lang);
        
        // Save latest message
        String lastSmsDisplay = "App: " + packageName + "\n\nBody: " + messageBody;
        prefs.edit().putString("last_sms", lastSmsDisplay).apply();

        PaymentEvent ev = null; // hoisted: the remote layer reuses this SAME eventId

        // Payment history capture (fire-and-forget; cannot affect the TTS call below).
        // The amount comes from SmsParser.extractAmount, the SAME parse that
        // produced the phrase above, so the stored number and the spoken
        // phrase can never disagree.
        try {
            SmsParser.AmountResult ar = smsParser.extractAmount(messageBody);
            ev = PaymentEvent.capture(
                    PaymentEvent.Source.NOTIFICATION, packageName, messageBody,
                    textToRead, lastSmsDisplay, ar.paise, ar.raw);
            PaymentHistoryStore.append(getApplicationContext(), ev);
        } catch (Exception ignored) {
        }

        // Speak via the foreground TTS service
        try {
            Intent svc = new Intent(getApplicationContext(), ForegroundTtsService.class);
            svc.setAction(ForegroundTtsService.ACTION_SPEAK);
            svc.putExtra(ForegroundTtsService.EXTRA_TEXT, textToRead);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(svc);
            } else {
                getApplicationContext().startService(svc);
            }
        } catch (Exception ignored) {
        }

        // Remote announcement (best-effort). Deliberately placed AFTER the local
        // TTS dispatch above, with no online check anywhere on the speech path.
        // RemoteAnnouncer checks the enable flag itself and never throws.
        try {
            if (ev != null) {
                RemoteAnnouncer.onPaymentCaptured(getApplicationContext(), ev);
            }
        } catch (Exception ignored) {
        }
    }
}
