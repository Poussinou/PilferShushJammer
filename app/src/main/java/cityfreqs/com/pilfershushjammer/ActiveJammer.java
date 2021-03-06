package cityfreqs.com.pilfershushjammer;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.Random;

public class ActiveJammer {
    private Context context;
    private AudioSettings audioSettings;

    private float amplitude;
    private int deviceMaxFrequency;
    private AudioTrack audioTrack;
    private boolean isPlaying;
    private int jammerTypeSwitch;
    private int userCarrier;
    private int userLimit;
    private int driftSpeed;
    private boolean eqOn;

    private Thread jammerThread;

    public ActiveJammer(Context context, AudioSettings audioSettings) {
        this.context = context;
        this.audioSettings = audioSettings;
        eqOn = false;
        // defaults
        jammerTypeSwitch = AudioSettings.JAMMER_TYPE_TEST;
        userCarrier = AudioSettings.CARRIER_NUHF_FREQUENCY;
        userLimit = AudioSettings.DEFAULT_RANGE_DRIFT_LIMIT;
        driftSpeed = AudioSettings.DEFAULT_DRIFT_SPEED;
        deviceMaxFrequency = AudioSettings.getDeviceMaxFrequency();

        resetActiveJammer();
    }

    private void resetActiveJammer() {
        amplitude = 1.0f;
        audioTrack = null;
        isPlaying = false;
    }

    /*
        PUBLIC CONTROLS
     */
    public void play(int type) {
        // FFT to find key NUHF freq in the environment and tune jammer to it?
        if (isPlaying) {
            return;
        }
        //stop();
        isPlaying = true;
        threadPlay(type);
    }

    public void stop() {
        isPlaying = false;
        if (audioTrack == null) {
            return;
        }
        stopPlayer();
    }

    public void setJammerTypeSwitch(int jammerTypeSwitch) {
        this.jammerTypeSwitch = jammerTypeSwitch;
    }
    public int getJammerTypeSwitch() {
        return jammerTypeSwitch;
    }

    public void setUserCarrier(int userCarrier) {
        userCarrier = checkCarrierFrequency(userCarrier);
        this.userCarrier = userCarrier;
    }
    public int getUserConformedCarrier() {
        // in setUserCarrier a check is performed, get this value
        return userCarrier;
    }


    public void setUserLimit(int userLimit) {
        this.userLimit = userLimit;
    }

    public void setDriftSpeed(int driftSpeed) {
        // is 1 - 10, then * 1000
        if (driftSpeed < 1) driftSpeed = 1;
        if (driftSpeed > 10) driftSpeed = 10;
        driftSpeed *= AudioSettings.DRIFT_SPEED_MULTIPLIER; // get into ms ranges
        this.driftSpeed = driftSpeed;
    }

    public void setEqOn(boolean eqOn) {
        this.eqOn = eqOn;
    }

    /*
        AUDIO PLAY FUNCTIONS
     */
    private synchronized void threadPlay(int typeIn) {
        final int type = typeIn;
        jammerThread = new Thread() {
            public void run() {
                try {
                    audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                            audioSettings.getSampleRate(),
                            audioSettings.getChannelOutConfig(),
                            audioSettings.getEncoding(),
                            audioSettings.getBufferOutSize(),
                            AudioTrack.MODE_STREAM);

                    audioTrack.setStereoVolume(amplitude, amplitude);

                    if (audioSettings.getHasEQ() && eqOn) {
                        onboardEQ(audioTrack.getAudioSessionId());
                    }

                    while (isPlaying) {
                        if (type == AudioSettings.JAMMER_TONE) {
                            createTone();
                        }
                        if (type == AudioSettings.JAMMER_WHITE) {
                            createWhiteNoise();
                        }
                    }
                }
                catch (Exception ex) {
                    //MainActivity.entryLogger(context.getResources().getString(R.string.active_state_1), true);
                    Log.d("PSJammer", context.getResources().getString(R.string.active_state_1));
                }
            }
        };
        jammerThread.start();
    }

    private void stopPlayer() {
        isPlaying = false;
        if (jammerThread != null) {
            try {
                jammerThread.interrupt();
                jammerThread.join();
                jammerThread = null;
            }
            catch (Exception ex) {
                MainActivity.entryLogger(context.getResources().getString(R.string.active_state_2), true);
            }
        }
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
                audioTrack = null;
            }
            catch (IllegalStateException e) {
                MainActivity.entryLogger(context.getResources().getString(R.string.active_state_3), true);
            }
        }
    }

    private synchronized int loadDriftTone() {
        switch (jammerTypeSwitch) {
            case AudioSettings.JAMMER_TYPE_TEST:
                return AudioSettings.getTestDrift();

            case AudioSettings.JAMMER_TYPE_NUHF:
                return AudioSettings.getNuhfDrift();

            case AudioSettings.JAMMER_TYPE_DEFAULT_RANGED:
                return AudioSettings.getDefaultRangedDrift(userCarrier);

            case AudioSettings.JAMMER_TYPE_USER_RANGED:
                return AudioSettings.getUserRangedDrift(userCarrier, userLimit);

            default:
                return AudioSettings.getTestDrift();
        }
    }

    private synchronized void createTone() {
        double sample[] = new double[audioSettings.getSampleRate()];
        byte soundData[] = new byte[2 * audioSettings.getSampleRate()];

        // NOTES: remove clicks from android audio emit, waveform at pop indicates no zero crossings either side
        // - AMPLITUDE RAMPS pre and post every loadDriftTone() etc - not practical
        // - ZERO VALUE SAMPLES either side of loadDriftTone()
        // - can still be useful jamming sound ;)

        /*

        int ramp = audioSettings.getSampleRate() / 20;
        for (int i = 0; i < sampleRate; i++) [
            if (jammerTypeSwitch != AudioSettings.JAMMER_TYPE_TEST && i % driftSpeed == 0) {...}

            if (i < ramp) sample[i] = 0;
            else if (i > sampleRate - ramp) sample[i] = 0;
            else {
                // normal loop
            }
        }

        */

        int driftFreq = loadDriftTone();
        // every nth iteration get a new drift freq (48k rate / driftSpeed )
        for (int i = 0; i < audioSettings.getSampleRate(); ++i) {
            if (jammerTypeSwitch != AudioSettings.JAMMER_TYPE_TEST && i % driftSpeed == 0) {
                driftFreq = loadDriftTone();
            }
            // ramp/zero-crossing check could go here
            sample[i] = Math.sin(
                    driftFreq * 2 * Math.PI * i / (audioSettings.getSampleRate()));
        }

        int idx = 0;
        for (final double dVal : sample) {
            final short val = (short) ((dVal * 32767)); // max the amplitude
            // in 16 bit wav PCM, first byte is the low order byte
            soundData[idx++] = (byte) (val & 0x00ff);
            soundData[idx++] = (byte) ((val & 0xff00) >>> 8);
        }
        playSound(soundData);
    }

    private synchronized void createWhiteNoise() {
        byte soundData[] = new byte[audioSettings.getSampleRate()];
        new Random().nextBytes(soundData);

        for (int i = 0; i < soundData.length; i++) {
            soundData[i] *= amplitude;
        }
        playSound(soundData);
    }

    private synchronized void playSound(byte[] soundData) {
        if (audioSettings == null) {
            MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_3), true);
            return;
        }

        try {
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play();
                audioTrack.write(soundData, 0, soundData.length);
            }
            else {
                MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_3), true);
            }
        }
        catch (Exception e) {
            MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_4), true);
        }
    }
    // this works reasonably well for the tone, but not whitenoise.
    private void onboardEQ(int audioSessionId) {
        try {
            Equalizer equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(true);
            short bands = equalizer.getNumberOfBands();
            final short minEQ = equalizer.getBandLevelRange()[0];
            final short maxEQ = equalizer.getBandLevelRange()[1];

            // attempt a HPF, to reduce (~15dB) all freqs in bands 0-3, boost band 4
            for (int i = 0; i < 2; i++) {
                for (short j = 0; j < bands; j++) {
                    equalizer.setBandLevel(j, minEQ);
                }
                // boost band 4 twice
                equalizer.setBandLevel((short)4, maxEQ);
            }
        }
        catch (Exception ex) {
            MainActivity.entryLogger("onboardEQ Exception.", true);
            ex.printStackTrace();
        }
    }

    private int checkCarrierFrequency(int carrierFrequency) {
        if (carrierFrequency > deviceMaxFrequency) {
            // note this, and restrict:
            MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_5) + deviceMaxFrequency, false);
            return deviceMaxFrequency;
        }
        else if (carrierFrequency < AudioSettings.MINIMUM_NUHF_FREQUENCY) {
            MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_6) + AudioSettings.MINIMUM_NUHF_FREQUENCY, false);
            return AudioSettings.MINIMUM_NUHF_FREQUENCY;
        }
        else {
            MainActivity.entryLogger(context.getResources().getString(R.string.audio_check_7) + carrierFrequency, false);
            return carrierFrequency;
        }
    }
}
