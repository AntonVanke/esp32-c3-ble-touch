package io.github.antonvanke.assistlab;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

// Voice-interaction metadata requires a recognizer; this test never records audio.
public final class NoSpeechService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try { callback.error(SpeechRecognizer.ERROR_CLIENT); }
        catch (RemoteException ignored) { }
    }
    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) { }
}
