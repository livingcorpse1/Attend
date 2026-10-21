package com.veriqra.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly typed configuration for every tunable the platform depends on.
 *
 * <p>Values are bound from {@code application.yml} and can be overridden with the
 * environment variables listed in {@code .env.example}. Nothing here holds a secret
 * that is not also overridable through an environment variable.
 */
@ConfigurationProperties(prefix = "veriqra")
public class AppProperties {

    /** HMAC secret used to sign rotating QR payloads. Must be at least 32 characters in production. */
    private String qrSecret = "local-development-signing-secret-change-before-deploy";

    /** IANA timezone used when rendering times for humans. Storage is always UTC. */
    private String timezone = "Asia/Kolkata";

    /** Percentage below which a student is reported as low attendance. */
    private int lowAttendanceThreshold = 75;

    /**
     * The name of the institution, shown in the shell.
     *
     * <p>There is no administration, so the college is named here. It is configuration,
     * not a constant in the code, so the same build can be pointed at any college.
     */
    private String institutionName = "";

    /** Where the institution is, shown under its name. */
    private String institutionLocation = "";



    private final Qr qr = new Qr();
    private final Biometric biometric = new Biometric();
    private final Storage storage = new Storage();
    private final Attendance attendance = new Attendance();
    private final Upload upload = new Upload();
    private final DemoData demoData = new DemoData();
    private final TeachingDay teachingDay = new TeachingDay();

    public String getQrSecret() {
        return qrSecret;
    }

    public void setQrSecret(String qrSecret) {
        this.qrSecret = qrSecret;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getLowAttendanceThreshold() {
        return lowAttendanceThreshold;
    }

    public void setLowAttendanceThreshold(int lowAttendanceThreshold) {
        this.lowAttendanceThreshold = lowAttendanceThreshold;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getInstitutionLocation() {
        return institutionLocation;
    }

    public void setInstitutionLocation(String institutionLocation) {
        this.institutionLocation = institutionLocation;
    }



    public Qr getQr() {
        return qr;
    }

    public Biometric getBiometric() {
        return biometric;
    }

    public Storage getStorage() {
        return storage;
    }

    public Attendance getAttendance() {
        return attendance;
    }

    public Upload getUpload() {
        return upload;
    }

    public DemoData getDemoData() {
        return demoData;
    }

    public TeachingDay getTeachingDay() {
        return teachingDay;
    }

    /** Whether the demonstration accounts are created on the first start. */
    public static class DemoData {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** The hours of the day in which lectures may be scheduled. */
    public static class TeachingDay {
        private String starts = "06:00";
        private String ends = "22:00";

        public String getStarts() {
            return starts;
        }

        public void setStarts(String starts) {
            this.starts = starts;
        }

        public String getEnds() {
            return ends;
        }

        public void setEnds(String ends) {
            this.ends = ends;
        }
    }

    /** Rotating QR token behaviour. */
    public static class Qr {
        /** Lifetime of a single QR payload in seconds. Kept short so a photographed code dies quickly. */
        private int ttlSeconds = 8;

        /** How often the teacher client refreshes the displayed code. */
        private int refreshSeconds = 1;

        /** Tolerated clock difference between the server and the browser, in seconds. */
        private int clockSkewSeconds = 5;

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getRefreshSeconds() {
            return refreshSeconds;
        }

        public void setRefreshSeconds(int refreshSeconds) {
            this.refreshSeconds = refreshSeconds;
        }

        public int getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(int clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    /** Server side face detection, liveness and matching thresholds. */
    public static class Biometric {
        /** Directory that holds the ONNX models. */
        private String modelsDirectory = "./models";

        /** Minimum YuNet detection score for a candidate face. */
        private float detectionThreshold = 0.80f;

        /** Minimum cosine similarity between the live sample and the enrolled template. */
        private float matchThreshold = 0.55f;

        /** Minimum absolute head-yaw delta, in eye-distance units, that proves a live head turn. */
        private float livenessYawDelta = 0.16f;

        /** Minimum cosine similarity required between every pair of frames of one capture. */
        private float frameConsistencyThreshold = 0.50f;

        /** Number of frames a client must submit for one capture. */
        private int requiredFrames = 5;

        /** Minimum ratio of the frame width that the face must occupy. */
        private float minimumFaceWidthRatio = 0.12f;

        /** Minimum sharpness (Laplacian variance) of the face crop. */
        private float minimumSharpness = 40f;

        /** Minimum mean brightness of the face crop. */
        private float minimumBrightness = 35f;

        /** Maximum mean brightness of the face crop. */
        private float maximumBrightness = 225f;

        /** Lifetime of a liveness challenge, in seconds. */
        private int challengeTtlSeconds = 120;

        /** Model identifier stored alongside an enrolment so templates can be migrated later. */
        private String modelVersion = "yunet-2023mar+sface-2021dec";

        public String getModelsDirectory() {
            return modelsDirectory;
        }

        public void setModelsDirectory(String modelsDirectory) {
            this.modelsDirectory = modelsDirectory;
        }

        public float getDetectionThreshold() {
            return detectionThreshold;
        }

        public void setDetectionThreshold(float detectionThreshold) {
            this.detectionThreshold = detectionThreshold;
        }

        public float getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(float matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public float getLivenessYawDelta() {
            return livenessYawDelta;
        }

        public void setLivenessYawDelta(float livenessYawDelta) {
            this.livenessYawDelta = livenessYawDelta;
        }

        public float getFrameConsistencyThreshold() {
            return frameConsistencyThreshold;
        }

        public void setFrameConsistencyThreshold(float frameConsistencyThreshold) {
            this.frameConsistencyThreshold = frameConsistencyThreshold;
        }

        public int getRequiredFrames() {
            return requiredFrames;
        }

        public void setRequiredFrames(int requiredFrames) {
            this.requiredFrames = requiredFrames;
        }

        public float getMinimumFaceWidthRatio() {
            return minimumFaceWidthRatio;
        }

        public void setMinimumFaceWidthRatio(float minimumFaceWidthRatio) {
            this.minimumFaceWidthRatio = minimumFaceWidthRatio;
        }

        public float getMinimumSharpness() {
            return minimumSharpness;
        }

        public void setMinimumSharpness(float minimumSharpness) {
            this.minimumSharpness = minimumSharpness;
        }

        public float getMinimumBrightness() {
            return minimumBrightness;
        }

        public void setMinimumBrightness(float minimumBrightness) {
            this.minimumBrightness = minimumBrightness;
        }

        public float getMaximumBrightness() {
            return maximumBrightness;
        }

        public void setMaximumBrightness(float maximumBrightness) {
            this.maximumBrightness = maximumBrightness;
        }

        public int getChallengeTtlSeconds() {
            return challengeTtlSeconds;
        }

        public void setChallengeTtlSeconds(int challengeTtlSeconds) {
            this.challengeTtlSeconds = challengeTtlSeconds;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public void setModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
        }
    }

    /** Where biometric enrolment images are stored. */
    public static class Storage {
        /** {@code local} writes to disk, {@code supabase} writes to a Supabase Storage bucket. */
        private String provider = "local";

        /** Base URL of the Supabase project, for example {@code https://abcdefgh.supabase.co}. */
        private String supabaseUrl = "";

        /** Service role key. Never exposed to the browser. */
        private String supabaseServiceRoleKey = "";

        /** Storage bucket that holds enrolment images. */
        private String bucket = "veriqra-biometrics";

        /** Directory used by the local storage provider. */
        private String localDirectory = "./data/storage";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getSupabaseUrl() {
            return supabaseUrl;
        }

        public void setSupabaseUrl(String supabaseUrl) {
            this.supabaseUrl = supabaseUrl;
        }

        public String getSupabaseServiceRoleKey() {
            return supabaseServiceRoleKey;
        }

        public void setSupabaseServiceRoleKey(String supabaseServiceRoleKey) {
            this.supabaseServiceRoleKey = supabaseServiceRoleKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getLocalDirectory() {
            return localDirectory;
        }

        public void setLocalDirectory(String localDirectory) {
            this.localDirectory = localDirectory;
        }
    }

    /** Attendance session and verification ticket behaviour. */
    public static class Attendance {
        /** How long a started attendance session stays open, in minutes. */
        private int sessionDurationMinutes = 180;

        /** Lifetime of the ticket handed out after a successful QR scan, in seconds. */
        private int verificationTicketTtlSeconds = 120;

        /** Minimum gap between two attendance submissions by the same student for the same session. */
        private int submissionIntervalSeconds = 3;

        /** Maximum number of failed login attempts allowed per identifier before the account is throttled. */
        private int maxLoginFailures = 8;

        /** Window, in minutes, over which failed login attempts are counted. */
        private int loginFailureWindowMinutes = 15;

        public int getSessionDurationMinutes() {
            return sessionDurationMinutes;
        }

        public void setSessionDurationMinutes(int sessionDurationMinutes) {
            this.sessionDurationMinutes = sessionDurationMinutes;
        }

        public int getVerificationTicketTtlSeconds() {
            return verificationTicketTtlSeconds;
        }

        public void setVerificationTicketTtlSeconds(int verificationTicketTtlSeconds) {
            this.verificationTicketTtlSeconds = verificationTicketTtlSeconds;
        }

        public int getSubmissionIntervalSeconds() {
            return submissionIntervalSeconds;
        }

        public void setSubmissionIntervalSeconds(int submissionIntervalSeconds) {
            this.submissionIntervalSeconds = submissionIntervalSeconds;
        }

        public int getMaxLoginFailures() {
            return maxLoginFailures;
        }

        public void setMaxLoginFailures(int maxLoginFailures) {
            this.maxLoginFailures = maxLoginFailures;
        }

        public int getLoginFailureWindowMinutes() {
            return loginFailureWindowMinutes;
        }

        public void setLoginFailureWindowMinutes(int loginFailureWindowMinutes) {
            this.loginFailureWindowMinutes = loginFailureWindowMinutes;
        }
    }

    /** Limits applied to inbound multipart uploads. */
    public static class Upload {
        /** Largest accepted single image, in bytes. */
        private long maxImageBytes = 3L * 1024 * 1024;

        /** Largest accepted request body for a capture upload, in bytes. */
        private long maxRequestBytes = 24L * 1024 * 1024;

        public long getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(long maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public long getMaxRequestBytes() {
            return maxRequestBytes;
        }

        public void setMaxRequestBytes(long maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
        }
    }
}
