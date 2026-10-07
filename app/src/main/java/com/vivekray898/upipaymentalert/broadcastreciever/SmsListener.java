package com.vivekray898.upipaymentalert.broadcastreciever;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsMessage;

import com.vivekray898.upipaymentalert.PaymentEvent;
import com.vivekray898.upipaymentalert.PaymentHistoryStore;
import com.vivekray898.upipaymentalert.remote.RemoteAnnouncer;
import com.vivekray898.upipaymentalert.smsparser.SmsParser;

import java.util.Locale;

public class SmsListener extends BroadcastReceiver {

    private final SmsParser smsParser = new SmsParser();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            StringBuilder messageBody = new StringBuilder();
            String address = "";
            for (SmsMessage smsMessage : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                address = smsMessage.getOriginatingAddress();
                messageBody.append(smsMessage.getMessageBody());
            }

            String textToDisplay = "Address: " + address + "\n\nBody: " + messageBody;
            
            SharedPreferences prefs = context.getSharedPreferences("UPI_PREFS", Context.MODE_PRIVATE);
            
            // Forwarding Logic
            String forwardNumber = prefs.getString("forwarder_number", "");
            if (!forwardNumber.isEmpty()) {
                String typeFilter = prefs.getString("forwarder_type_filter", "Credited");
                boolean shouldForward = false;
                if ("Credited".equals(typeFilter) && smsParser.isCreditTransaction(messageBody.toString())) {
                    shouldForward = true;
                } else if ("Debited".equals(typeFilter) && smsParser.isDebitTransaction(messageBody.toString())) {
                    shouldForward = true;
                } else if ("Both".equals(typeFilter) && (smsParser.isCreditTransaction(messageBody.toString()) || smsParser.isDebitTransaction(messageBody.toString()))) {
                    shouldForward = true;
                }

                if (shouldForward) {
                    try {
                        android.telephony.SmsManager.getDefault().sendTextMessage(forwardNumber, null, "FWD SMS: " + messageBody.toString(), null, null);
                    } catch (Exception ignored) {}
                }
            }

            // Check if it is a credit transaction for TTS announcement
            if (!smsParser.isCreditTransaction(messageBody.toString())) {
                return;
            }

            String lang = prefs.getString("language", "English");
            String textToRead = smsParser.getAmountFromMessageBody(messageBody.toString(), lang);

            // Save latest message so UI can pick it up when opened
            prefs.edit().putString("last_sms", textToDisplay).apply();

            PaymentEvent ev = null; // hoisted: the remote layer reuses this SAME eventId

            // Payment history capture (fire-and-forget; cannot affect the TTS call below).
            // The amount comes from SmsParser.extractAmount, the SAME parse that
            // produced the phrase above, so the stored number and the spoken
            // phrase can never disagree.
            try {
                SmsParser.AmountResult ar = smsParser.extractAmount(messageBody.toString());
                ev = PaymentEvent.capture(
                        PaymentEvent.Source.SMS, address, messageBody.toString(),
                        textToRead, textToDisplay, ar.paise, ar.raw);
                PaymentHistoryStore.append(context, ev);
            } catch (Exception ignored) {
            }

            // Speak via the foreground TTS service (start or deliver intent)
            try {
                Intent svc = new Intent(context.getApplicationContext(), com.vivekray898.upipaymentalert.ForegroundTtsService.class);
                svc.setAction(com.vivekray898.upipaymentalert.ForegroundTtsService.ACTION_SPEAK);
                svc.putExtra(com.vivekray898.upipaymentalert.ForegroundTtsService.EXTRA_TEXT, textToRead);
                // Start the service (foreground service will keep running)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.getApplicationContext().startForegroundService(svc);
                } else {
                    context.getApplicationContext().startService(svc);
                }
            } catch (Exception ignored) {
            }

            // Remote announcement (best-effort). Deliberately placed AFTER the
            // local TTS dispatch above, with no online check anywhere on the
            // speech path: local speech must fire whether or not the network,
            // the crypto or the relay works. RemoteAnnouncer checks the enable
            // flag itself and never throws.
            try {
                if (ev != null) {
                    RemoteAnnouncer.onPaymentCaptured(context.getApplicationContext(), ev);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
