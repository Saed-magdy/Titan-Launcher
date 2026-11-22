package com.dongfeng.shinecockpit;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import com.qinggan.canbus.AirConditionState;
import com.qinggan.canbus.VehicleState;

/**
 * Direct Binder Interface for Qinggan / Dongfeng Automotive CAN Bus System ("qg.canbus").
 * Guarantees zero conflict / zero collision with existing vehicle services.
 */
public class CanBusClient {
    private static final String TAG = "DongfengCockpitCAN";
    private static final String SERVICE_NAME = "qg.canbus";
    private static final String DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    
    // Transaction code 52 = setVehicleState(VehicleState state, int value)
    private static final int TRANSACTION_SET_VEHICLE_STATE = 52;
    // Transaction code 51 = int a(VehicleState vehicleState) [getVehicleState]
    private static final int TRANSACTION_GET_VEHICLE_STATE = 51;
    // Transaction code 30 = setAirCondition(AirConditionState state, int value)
    private static final int TRANSACTION_SET_AIR_CONDITION = 30;
    // Transaction code 25 = int y() -> N() -> this.b [getSpeed in km/h directly from CAN bus]
    private static final int TRANSACTION_GET_SPEED = 25;
    // Transaction code 2 = ce b() [getDoorStatus: FL, FR, RL, RR, trunk, bonnet]
    private static final int TRANSACTION_GET_DOORS = 2;
    // Transaction code 8 = cf h() [getFuelLevel]
    private static final int TRANSACTION_GET_FUEL = 8;
    // Transaction code 1 = cp a() [getOdometer]
    private static final int TRANSACTION_GET_ODOMETER = 1;
    // Transaction code 29 = bt A() [getAirCondition]
    private static final int TRANSACTION_GET_AC = 29;
    // Transaction code 4 = cj d() [getLightStatus]
    private static final int TRANSACTION_GET_LIGHTS = 4;
    // Transaction code 5 = GearState e() [getGearState: P, R, N, D, M]
    private static final int TRANSACTION_GET_GEAR = 5;
    // Transaction code 41 = cw J() [getSWCAngle: Steering Wheel Column Angle sensor]
    private static final int TRANSACTION_GET_SWC_ANGLE = 41;
    // Transaction code 66 = da P() [getTPMSInfo: Tire Pressure Monitoring System]
    private static final int TRANSACTION_GET_TPMS = 66;
    // Transaction code 67 = float Q() -> av() [Direct Instantaneous Fuel Consumption]
    private static final int TRANSACTION_GET_INSTANT_FUEL = 67;
    // Transaction code 68 = cr R() -> getPowerLevel [mInstantaneousPowerConsumption]
    private static final int TRANSACTION_GET_POWER_LEVEL = 68;
    // Transaction code 63 = int N() -> getAccState [0=OFF, 1=ACC, 2=ON, 3=START]
    private static final int TRANSACTION_GET_ACC_STATE = 63;
    // Transaction code 43 = df L() [WindowStatus: windows, sunroof, roll, tilt]
    private static final int TRANSACTION_GET_WINDOWS = 43;

    private IBinder mServiceBinder = null;
    private volatile boolean mIsConnected = false;
    private Context mContext = null;
    private boolean mIsBound = false;

    // Live CAN UEvent tracking for physical Left/Right turn signals (CAN frame 71 / 0x47)
    private Object mCanUEventObserver = null;
    private volatile boolean mStalkLeft = false;
    private volatile boolean mStalkRight = false;
    private volatile long mLastTurnLeftOnMs = 0L;
    private volatile long mLastTurnRightOnMs = 0L;
    private volatile boolean mUEventHazard = false;

    // Live CAN speed & fuel tracking for responsive Instantaneous Fuel Consumption
    private float mLastValidAvgFuel = 6.3f;
    private int mLastSpeed = 0;
    private long mLastSpeedTimeMs = 0L;
    private float mSmoothedInstFuel = 0.8f;

    // C65 Sunroof tilt latch (C65 CAN sets sr=2 for both open and tilt, while sunroofTilt stays 0)
    private volatile boolean mSunroofTilted = false;
    private volatile long mSunroofTiltStartMs = 0L;
    private volatile boolean mSunroofTiltSawSr2 = false;
    private volatile int mSunroofTiltBaselinePos = -1;

    // Cached slow-changing lock preferences (refreshed every 5s or on write)
    private volatile int mCachedSpeedLock = -1;
    private volatile int mCachedWalkLock = -1;
    private volatile int mCachedOffUnlock = -1;
    private volatile long mLastLocksPollMs = 0L;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "CanBusService connected via bindService: " + name);
            mServiceBinder = service;
            mIsConnected = (service != null && service.isBinderAlive());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "CanBusService disconnected from: " + name);
            mServiceBinder = null;
            mIsConnected = false;
            mIsBound = false;
        }
    };

    public CanBusClient() {
        this(null);
    }

    public CanBusClient(Context context) {
        this.mContext = context;
        connectService();
        // Disabled UEventObserver(21, ':') because bind() fails with EPERM under non-system UID,
        // leaving socket_fd=0 and starving CPU in a native poll() loop in libqg_runtime.so.
        startAutoReconnectLoop();
    }

    private void processStalkByte(int dataByte) {
        if (dataByte < 0) return;
        // Bit 3 = Left turn (clightleft)
        boolean leftBit = ((dataByte >> 3) & 1) == 1;
        // Bit 1 = Right turn (clightright)
        boolean rightBit = ((dataByte >> 1) & 1) == 1;
        // Bit 6 = Hazard / Caution light
        boolean hazardBit = ((dataByte >> 6) & 1) == 1;

        long now = System.currentTimeMillis();
        if (leftBit) {
            mStalkLeft = true;
            mLastTurnLeftOnMs = now;
            if (!rightBit) {
                mStalkRight = false;
                mLastTurnRightOnMs = 0L;
            }
        } else {
            mStalkLeft = false;
            mLastTurnLeftOnMs = 0L;
        }

        if (rightBit) {
            mStalkRight = true;
            mLastTurnRightOnMs = now;
            if (!leftBit) {
                mStalkLeft = false;
                mLastTurnLeftOnMs = 0L;
            }
        } else {
            mStalkRight = false;
            mLastTurnRightOnMs = 0L;
        }
        mUEventHazard = hazardBit;

        Log.i(TAG, "CAN Stalk 0x92/0x47 UEvent: raw=0x" + Integer.toHexString(dataByte)
                + " (" + dataByte + ") L=" + leftBit + " R=" + rightBit + " Haz=" + hazardBit);
    }

    private void processUEventInts(int[] buf) {
        if (buf == null || buf.length < 3) return;
        // Case 1: Look for 0x92 (146), cmd 0x10 (16), dataByte (from dls_reverse_svr stalk handler at 0x224F4)
        for (int i = 0; i <= buf.length - 3; i++) {
            int b0 = buf[i] & 0xFF;
            int b1 = buf[i + 1] & 0xFF;
            if ((b0 == 0x92 || b0 == 146) && (b1 == 0x10 || b1 == 16)) {
                processStalkByte(buf[i + 2] & 0xFF);
                return;
            }
        }
        // Case 2: Packet starts with 0x92 (146) directly
        if ((buf[0] & 0xFF) == 0x92 || (buf[0] & 0xFF) == 146) {
            processStalkByte(buf[2] & 0xFF);
            return;
        }
        // Case 3: Packet starts with 0x47 (71) legacy CAN frame ID
        if ((buf[0] & 0xFF) == 71 || (buf[0] & 0xFF) == 0x47) {
            processStalkByte(buf[2] & 0xFF);
            return;
        }
        // Case 4: Any packet containing 0x92 in the first 2 bytes
        if (buf.length >= 4 && ((buf[1] & 0xFF) == 0x92 || (buf[1] & 0xFF) == 146)) {
            processStalkByte(buf[3] & 0xFF);
            return;
        }
    }

    private void processUEventString(String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            int idx = msg.indexOf("0x92");
            if (idx == -1) idx = msg.indexOf("92-");
            if (idx == -1) idx = msg.indexOf("92:");
            if (idx != -1) {
                String sub = msg.substring(idx).replaceAll("[^0-9a-fA-FxX]", " ");
                String[] tokens = sub.trim().split("\\s+");
                if (tokens.length >= 3) {
                    int b0 = parseHexOrDec(tokens[0]);
                    int b1 = parseHexOrDec(tokens[1]);
                    if ((b0 == 0x92 || b0 == 146) && (b1 == 0x10 || b1 == 16)) {
                        int dataByte = parseHexOrDec(tokens[2]);
                        processStalkByte(dataByte);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int parseHexOrDec(String s) {
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s, 16);
        } catch (Exception e) {
            try {
                return Integer.parseInt(s);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    private void startCanUEventObserver() {
        // Intentionally disabled: Qinggan native UEventObserver(21, ':') fails bind() with errno=1
        // (Operation not permitted) for third-party/platform-signed app UIDs and falls back to polling
        // socket_fd=0 in an infinite ~98% CPU loop inside libqg_runtime.so (PateoUEventObserver).
    }

    private void startAutoReconnectLoop() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2500);
                        if (!isConnected()) {
                            connectService();
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {}
                }
            }
        }, "CanBusReconnectThread");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized boolean manualReconnect() {
        Log.i(TAG, "manualReconnect requested by user/UI");
        mIsConnected = false;
        mIsBound = false;
        return connectService();
    }

    public synchronized boolean connectService() {
        if (isConnected()) {
            return true;
        }

        // Strategy 1: Android Framework bindService to exported CanBusService
        if (mContext != null && !mIsBound) {
            try {
                Intent intent = new Intent("com.qinggan.canbus.CanBusService");
                intent.setPackage("com.qinggan.canbus.service");
                boolean bound = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bindService with action/package result: " + bound);
                if (bound) {
                    mIsBound = true;
                } else {
                    Intent intentComp = new Intent();
                    intentComp.setComponent(new ComponentName("com.qinggan.canbus.service", "com.qinggan.canbus.service.CanBusService"));
                    bound = mContext.bindService(intentComp, mServiceConnection, Context.BIND_AUTO_CREATE);
                    Log.i(TAG, "bindService with ComponentName result: " + bound);
                    if (bound) mIsBound = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "bindService failed", e);
            }
        }

        if (isConnected()) {
            return true;
        }

        // Strategy 2: Direct ServiceManager getService reflection across all known candidate names
        String[] candidateNames = new String[] { "qg.canbus", "canbus", "com.qinggan.canbus", "qg_canbus", "car_service" };
        for (String name : candidateNames) {
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
                IBinder binder = (IBinder) getService.invoke(null, name);
                if (binder != null && binder.isBinderAlive()) {
                    mServiceBinder = binder;
                    mIsConnected = true;
                    Log.i(TAG, "Connected to ServiceManager: " + name);
                    return true;
                }
            } catch (Exception ignored) {}
        }

        mIsConnected = (mServiceBinder != null && mServiceBinder.isBinderAlive());
        return mIsConnected;
    }

    public boolean isConnected() {
        return mIsConnected && mServiceBinder != null && mServiceBinder.isBinderAlive();
    }

    /**
     * Set Vehicle State via official AIDL Binder Transaction 52
     */
    public boolean setVehicleState(VehicleState state, int value) {
        if (!isConnected()) {
            if (!connectService()) {
                Log.d(TAG, "[MOCK/SIM] setVehicleState: " + (state != null ? state.name() : "null") + " -> " + value);
                return true;
            }
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // Write interface token for remote AIDL service
            data.writeInterfaceToken(DESCRIPTOR);
            if (state != null) {
                data.writeInt(1);
                state.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            data.writeInt(value);

            boolean res = mServiceBinder.transact(TRANSACTION_SET_VEHICLE_STATE, data, reply, 0);
            reply.readException();
            Log.i(TAG, "Sent CAN command: " + (state != null ? state.name() : "null") + " = " + value + " (Res: " + res + ")");
            return res;
        } catch (Exception e) {
            Log.e(TAG, "Error executing setVehicleState", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Set A/C State via official AIDL Binder Transaction 30
     */
    public boolean setAirCondition(AirConditionState state, int value) {
        if (!isConnected()) {
            if (!connectService()) {
                Log.d(TAG, "[MOCK/SIM] setAirCondition: " + (state != null ? state.name() : "null") + " -> " + value);
                return true;
            }
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // Write interface token for remote AIDL service
            data.writeInterfaceToken(DESCRIPTOR);
            if (state != null) {
                data.writeInt(1);
                state.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            data.writeInt(value);

            boolean res = mServiceBinder.transact(TRANSACTION_SET_AIR_CONDITION, data, reply, 0);
            reply.readException();
            Log.i(TAG, "Sent CAN AC command: " + (state != null ? state.name() : "null") + " = " + value + " (Res: " + res + ")");
            return res;
        } catch (Exception e) {
            Log.e(TAG, "Error executing setAirCondition", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static class TelemetrySnapshot {
        public int speed = 0;
        public float fuelRemain = 17.1f;
        public float fuelCapacity = 55.0f;
        public float fuelPercentage = 31.0f;
        public float instFuel = 0.0f;
        public float avgFuel = 6.3f;
        public int dte = 290;
        public boolean isFuelShortage = false;
        public int totalMileage = 14593;
        public int outsideTemp = 22;
        public int coolantTemp = 90;
        public boolean lowBeam = false;
        public boolean highBeam = false;
        public boolean fogFront = false;
        public boolean posLight = false;
        public boolean hazard = false;
        public boolean dirL = false;
        public boolean dirR = false;
        public int doorFL = 0;
        public int doorFR = 0;
        public int doorRL = 0;
        public int doorRR = 0;
        public int doorTrunk = 0;
        public int doorHood = 0;
        public int swcDirection = 0; // 0=center, 1=left, 2=right
        public int swcAngle = 0;     // degrees
        public float tpmsFL = 2.3f;
        public float tpmsFR = 2.3f;
        public float tpmsRL = 2.3f;
        public float tpmsRR = 2.3f;
        public boolean tpmsFLWarn = false;
        public boolean tpmsFRWarn = false;
        public boolean tpmsRLWarn = false;
        public boolean tpmsRRWarn = false;
        public boolean sportMode = false;
        public boolean canConnected = false;
        public int speedLock = -1;  // AutomaticLocking: 3 = ON, 1 = OFF, -1 = unknown
        public int walkLock = -1;   // WALK_LOCK_SWITCH: 1 = ON, 0 = OFF, -1 = unknown
        public int offUnlock = -1;  // ShutdownAutoUlockSwitch: 1 = ON, 0 = OFF, -1 = unknown
        public String gear = "P";   // P, R, N, D, M
        public String sunroofState = "close"; // "close", "open", "tilt"
        public int sunroofPos = 0;            // 0=closed, >0=open pos
        public int sunroofTilt = 0;           // 0=flat, 1=tilted
    }

    /**
     * Query any VehicleState directly from CAN bus via Transaction 51 or fallback
     */
    public int getVehicleState(VehicleState state) {
        if (!isConnected()) {
            if (!connectService()) {
                if (mContext != null && state != null) {
                    try {
                        return android.provider.Settings.System.getInt(mContext.getContentResolver(), state.name(), -1);
                    } catch (Exception ignored) {}
                }
                return -1;
            }
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (state != null) {
                data.writeInt(1);
                state.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            boolean res = mServiceBinder.transact(TRANSACTION_GET_VEHICLE_STATE, data, reply, 0);
            if (res) {
                reply.readException();
                int val = reply.readInt();
                Log.d(TAG, "getVehicleState from CAN: " + (state != null ? state.name() : "null") + " = " + val);
                return val;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in getVehicleState: " + (state != null ? state.name() : "null"), e);
        } finally {
            data.recycle();
            reply.recycle();
        }
        if (mContext != null && state != null) {
            try {
                return android.provider.Settings.System.getInt(mContext.getContentResolver(), state.name(), -1);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public int getSpeed() {
        if (!isConnected()) {
            if (!connectService()) return -1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            boolean res = mServiceBinder.transact(TRANSACTION_GET_SPEED, data, reply, 0);
            if (res) {
                reply.readException();
                return reply.readInt();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting speed", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return -1;
    }

    public float getInstantFuel() {
        if (!isConnected()) {
            if (!connectService()) return -1.0f;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            boolean res = mServiceBinder.transact(TRANSACTION_GET_INSTANT_FUEL, data, reply, 0);
            if (res) {
                reply.readException();
                return reply.readFloat();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting instant fuel", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return -1.0f;
    }

    public TelemetrySnapshot getTelemetrySnapshot() {
        return getTelemetrySnapshot(false);
    }

    public TelemetrySnapshot getTelemetrySnapshot(boolean lightMode) {
        TelemetrySnapshot snap = new TelemetrySnapshot();
        snap.canConnected = isConnected();
        if (!isConnected()) {
            if (!connectService()) return snap;
        }
        snap.canConnected = isConnected();

        // 1. Speed (Transaction 6)
        int spd = getSpeed();
        if (spd >= 0) snap.speed = spd;

        // 2. Fuel Level (Transaction 8)
        Parcel data8 = Parcel.obtain();
        Parcel reply8 = Parcel.obtain();
        try {
            data8.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_FUEL, data8, reply8, 0)) {
                reply8.readException();
                if (reply8.readInt() != 0) {
                    snap.fuelCapacity = reply8.readInt();
                    snap.fuelRemain = reply8.readInt();
                    float rawPct = reply8.readFloat();
                    if (rawPct > 0.0f && rawPct <= 1.0f) {
                        snap.fuelPercentage = rawPct * 100.0f;
                    } else if (rawPct > 1.0f && rawPct <= 100.0f) {
                        snap.fuelPercentage = rawPct;
                    }
                    snap.isFuelShortage = (reply8.readInt() == 1);
                    float inst = reply8.readFloat();
                    float avg = reply8.readFloat();
                    if (inst >= 0.0f && inst <= 50.0f) {
                        snap.instFuel = inst;
                    }
                    if (avg > 0.0f && avg <= 50.0f) {
                        snap.avgFuel = avg;
                        mLastValidAvgFuel = avg;
                    } else {
                        snap.avgFuel = mLastValidAvgFuel;
                    }
                }
            }
        } catch (Exception ignored) {}
        finally {
            data8.recycle();
            reply8.recycle();
        }

        // Supplementary instantaneous fuel check via Transaction 67 (float Q()) if inst was 0 or omitted
        if (snap.instFuel <= 0.01f) {
            float qVal = getInstantFuel();
            if (qVal > 0.01f && qVal <= 50.0f) {
                snap.instFuel = qVal;
            }
        }

        // Also check Transaction 68 (PowerLevel.mInstantaneousPowerConsumption) when moving in full mode
        if (!lightMode && snap.speed > 0 && snap.instFuel <= 0.01f) {
            Parcel data68 = Parcel.obtain();
            Parcel reply68 = Parcel.obtain();
            try {
                data68.writeInterfaceToken(DESCRIPTOR);
                if (mServiceBinder.transact(TRANSACTION_GET_POWER_LEVEL, data68, reply68, 0)) {
                    reply68.readException();
                    if (reply68.readInt() != 0) {
                        reply68.readInt(); // capacity
                        reply68.readInt(); // remain
                        reply68.readFloat(); // percentage
                        reply68.readInt(); // shortage
                        float pInst = reply68.readFloat();
                        if (pInst > 0.01f && pInst <= 50.0f) {
                            snap.instFuel = pInst;
                        }
                    }
                }
            } catch (Exception ignored) {}
            finally {
                data68.recycle();
                reply68.recycle();
            }
        }

        // Query ACC / Ignition state (Transaction 63 -> int N())
        int accState = 2;
        Parcel data63 = Parcel.obtain();
        Parcel reply63 = Parcel.obtain();
        try {
            data63.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_ACC_STATE, data63, reply63, 0)) {
                reply63.readException();
                accState = reply63.readInt();
            }
        } catch (Exception ignored) {}
        finally {
            data63.recycle();
            reply63.recycle();
        }

        // On Dongfeng C65 petrol (b.java), CAN 0x380 provides comprehensive/avg fuel + live speed,
        // while FuelLevel.mInstantaneousFuelConsumption is left at 0.0f by the OEM service.
        // Compute real-time CAN-driven instantaneous fuel from live CAN speed, acceleration (dv/dt), and avgFuel:
        long nowMs = System.currentTimeMillis();
        float dtSec = (mLastSpeedTimeMs > 0L) ? Math.max(0.2f, (nowMs - mLastSpeedTimeMs) / 1000.0f) : 0.8f;
        float accelKmhPerSec = (snap.speed - mLastSpeed) / dtSec;
        mLastSpeed = snap.speed;
        mLastSpeedTimeMs = nowMs;

        if (snap.instFuel <= 0.01f) {
            float baseAvg = (snap.avgFuel > 0.5f && snap.avgFuel <= 35.0f) ? snap.avgFuel : mLastValidAvgFuel;
            float targetInst;
            if (snap.speed <= 0) {
                // Idle consumption when ignition ON (0.8 L/h equivalent), 0.0 when OFF
                targetInst = (accState >= 2) ? 0.8f : 0.0f;
            } else {
                // Speed efficiency factor + live acceleration load from CAN bus speed delta
                float speedFactor;
                if (snap.speed < 25) {
                    speedFactor = 1.25f - (snap.speed / 25.0f) * 0.20f;
                } else if (snap.speed <= 85) {
                    speedFactor = 0.88f + Math.abs(snap.speed - 65) * 0.003f;
                } else {
                    speedFactor = 0.95f + (snap.speed - 85) * 0.008f;
                }
                float accelLoad = accelKmhPerSec * 0.85f;
                targetInst = baseAvg * speedFactor + accelLoad;
                if (accelKmhPerSec < -2.2f) {
                    // Deceleration Fuel Cut-Off (DFCO) when coasting/braking
                    targetInst = Math.max(1.2f, baseAvg * 0.25f);
                }
                targetInst = Math.max(1.2f, Math.min(29.9f, targetInst));
            }
            mSmoothedInstFuel = (mSmoothedInstFuel * 0.55f) + (targetInst * 0.45f);
            snap.instFuel = Math.round(mSmoothedInstFuel * 10.0f) / 10.0f;
        }

        // Compute high-precision remaining liters from percentage and tank capacity
        float cap = snap.fuelCapacity > 0 ? snap.fuelCapacity : 55.0f;
        if (snap.fuelPercentage > 0) {
            snap.fuelRemain = cap * (snap.fuelPercentage / 100.0f);
        }

        // 3. Odometer & DTE (Transaction 1)
        Parcel data1 = Parcel.obtain();
        Parcel reply1 = Parcel.obtain();
        try {
            data1.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_ODOMETER, data1, reply1, 0)) {
                reply1.readException();
                if (reply1.readInt() != 0) {
                    snap.dte = reply1.readInt(); // mCanTravelMileage
                    snap.totalMileage = reply1.readInt(); // mBeenTravelingMileage
                }
            }
        } catch (Exception ignored) {}
        finally {
            data1.recycle();
            reply1.recycle();
        }

        // 4. Outside Temp (Transaction 29 - AirCondition)
        Parcel data29 = Parcel.obtain();
        Parcel reply29 = Parcel.obtain();
        try {
            data29.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_AC, data29, reply29, 0)) {
                reply29.readException();
                if (reply29.readInt() != 0) {
                    for (int i = 0; i < 10; i++) reply29.readInt();
                    for (int i = 0; i < 3; i++) reply29.readFloat();
                    for (int i = 0; i < 21; i++) reply29.readInt();
                    int outTemp = reply29.readInt();
                    if (outTemp >= -40 && outTemp <= 70) snap.outsideTemp = outTemp;
                    else snap.outsideTemp = 24;
                }
            }
        } catch (Exception ignored) {}
        finally {
            data29.recycle();
            reply29.recycle();
        }

        if (lightMode) {
            snap.speedLock = mCachedSpeedLock;
            snap.walkLock = mCachedWalkLock;
            snap.offUnlock = mCachedOffUnlock;
            return snap;
        }

        pollFastControls(snap);

        // 8. TPMS & 10. Locks follow after fast controls
        return completeSlowSnapshot(snap);
    }

    /** Lightweight ~120ms snapshot for fast 3D controls (lights, doors, steering, gear, speed). */
    public TelemetrySnapshot getFastControlSnapshot() {
        TelemetrySnapshot snap = new TelemetrySnapshot();
        snap.canConnected = isConnected();
        if (!isConnected()) {
            if (!connectService()) return snap;
        }
        snap.canConnected = isConnected();
        int spd = getSpeed();
        if (spd >= 0) snap.speed = spd;
        pollFastControls(snap);
        return snap;
    }

    private void pollFastControls(TelemetrySnapshot snap) {
        // 5. Light Status (Transaction 4 + C65 VehicleState fallback)
        Parcel data4 = Parcel.obtain();
        Parcel reply4 = Parcel.obtain();
        try {
            data4.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_LIGHTS, data4, reply4, 0)) {
                reply4.readException();
                if (reply4.readInt() != 0) {
                    int dirL = reply4.readInt(); // a: directionL
                    int dirR = reply4.readInt(); // b: directionR
                    int fogF = reply4.readInt(); // c: fogFront
                    int fogR = reply4.readInt(); // d: fogRear (C65 stores rear fog here)
                    int mainBeam = reply4.readInt(); // e: mainBeam
                    int sideLight = reply4.readInt(); // f: sideLight
                    int ebdOrHighBeam = reply4.readInt(); // g: C65 CanBusComponentImpl stores HIGH_BEAM in l.g!
                    int dipped = reply4.readInt(); // h: dippedBeam (Low Beam)
                    int posLamp = reply4.readInt(); // i: positionLamp (C65 stores 1=ON, 0=OFF)
                    reply4.readInt(); // j: dayLight
                    reply4.readInt(); // k: stopLight
                    reply4.readInt(); // l: reversLight
                    int caution = reply4.readInt(); // m: cautionLight (Hazard)
                    snap.dirL = (dirL == 2);
                    snap.dirR = (dirR == 2);
                    snap.fogFront = (fogF == 2 || fogR == 2);
                    snap.highBeam = (mainBeam == 2 || ebdOrHighBeam == 2);
                    snap.lowBeam = (dipped == 2);
                    snap.posLight = (sideLight == 2 || posLamp == 1 || posLamp == 2);
                    snap.hazard = (caution == 2 || (snap.dirL && snap.dirR));
                }
            }
        } catch (Exception ignored) {}
        finally {
            data4.recycle();
            reply4.recycle();
        }
        try {
            if (!snap.posLight) {
                int pSt = getVehicleState(VehicleState.PositionLamp);
                int lSt = getVehicleState(VehicleState.LITTLE_LIGHT_STATUS);
                if (pSt == 1 || pSt == 2 || lSt == 2) snap.posLight = true;
            }
            if (!snap.highBeam && getVehicleState(VehicleState.HIGH_BEAM) == 2) {
                snap.highBeam = true;
            }
            if (!snap.lowBeam && getVehicleState(VehicleState.LOW_BEAM) == 2) {
                snap.lowBeam = true;
            }
            if (!snap.fogFront && getVehicleState(VehicleState.FOG_LIGHT) == 2) {
                snap.fogFront = true;
            }
            // Real hazard is active only when hazard caution light is on or both turn signals flash together
            if (snap.dirL && snap.dirR) {
                snap.hazard = true;
            }
            long nowTurnMs = System.currentTimeMillis();
            boolean stalkL = mStalkLeft || (mLastTurnLeftOnMs > 0L && (nowTurnMs - mLastTurnLeftOnMs) <= 1200L);
            boolean stalkR = mStalkRight || (mLastTurnRightOnMs > 0L && (nowTurnMs - mLastTurnRightOnMs) <= 1200L);
            if (stalkL) snap.dirL = true;
            if (stalkR) snap.dirR = true;
        } catch (Exception ignored) {}

        // 6. Door Status (Transaction 2)
        Parcel data2 = Parcel.obtain();
        Parcel reply2 = Parcel.obtain();
        try {
            data2.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_DOORS, data2, reply2, 0)) {
                reply2.readException();
                if (reply2.readInt() != 0) {
                    snap.doorHood = reply2.readInt();
                    snap.doorFL = reply2.readInt();
                    reply2.readInt(); // flLock
                    snap.doorFR = reply2.readInt();
                    reply2.readInt(); // frLock
                    snap.doorTrunk = reply2.readInt();
                    snap.doorRL = reply2.readInt();
                    reply2.readInt(); // rlLock
                    snap.doorRR = reply2.readInt();
                    reply2.readInt(); // rrLock
                }
            }
        } catch (Exception ignored) {}
        finally {
            data2.recycle();
            reply2.recycle();
        }

        // 7. Steering Wheel Angle (Transaction 41 -> cw J())
        Parcel data41 = Parcel.obtain();
        Parcel reply41 = Parcel.obtain();
        try {
            data41.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_SWC_ANGLE, data41, reply41, 0)) {
                reply41.readException();
                if (reply41.readInt() != 0) {
                    snap.swcDirection = reply41.readInt(); // a: mWheelDirection
                    int rawSwcAngle = reply41.readInt();     // b: mWheelAngle
                    if (rawSwcAngle > 32767) {
                        rawSwcAngle -= 65536;
                    }
                    snap.swcAngle = rawSwcAngle;
                }
            }
        } catch (Exception ignored) {}
        finally {
            data41.recycle();
            reply41.recycle();
        }

        // 7b. Gear State (Transaction 5 -> GearState e())
        Parcel data5 = Parcel.obtain();
        Parcel reply5 = Parcel.obtain();
        try {
            data5.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_GEAR, data5, reply5, 0)) {
                reply5.readException();
                if (reply5.readInt() != 0) {
                    int ord = reply5.readInt();
                    if (ord == 0) snap.gear = "P";
                    else if (ord == 1) snap.gear = "R";
                    else if (ord == 2) snap.gear = "N";
                    else if (ord == 3) snap.gear = "D";
                    else if (ord == 4) snap.gear = "M";
                }
            }
        } catch (Exception ignored) {}
        finally {
            data5.recycle();
            reply5.recycle();
        }

    }

    private TelemetrySnapshot completeSlowSnapshot(TelemetrySnapshot snap) {
        // 8. TPMS Real-Time Tire Pressures & Warnings (Transaction 66 -> da P())
        Parcel data66 = Parcel.obtain();
        Parcel reply66 = Parcel.obtain();
        try {
            data66.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_TPMS, data66, reply66, 0)) {
                reply66.readException();
                if (reply66.readInt() != 0) {
                    reply66.readInt(); // a: mTirePositionStatus
                    reply66.readInt(); // b: mTemperatureWarningStatus
                    reply66.readInt(); // c: mSystemWaringStatus
                    int warnFL = reply66.readInt(); // d: mLeftFrontTireWarningStatus
                    int warnFR = reply66.readInt(); // e: mRightFrontTireWarningStatus
                    int warnRL = reply66.readInt(); // f: mLeftRearTireWarningStatus
                    int warnRR = reply66.readInt(); // g: mRightRearTireWarningStatus
                    float pFL = reply66.readFloat(); // h: mLeftFrontTirePressureValue
                    float pFR = reply66.readFloat(); // i: mRightFrontTirePressureValue
                    float pRL = reply66.readFloat(); // j: mLeftRearTirePressureValue
                    float pRR = reply66.readFloat(); // k: mRightRearTirePressureValue

                    if (pFL > 0.5f && pFL < 5.0f) snap.tpmsFL = Math.round(pFL * 10.0f) / 10.0f;
                    if (pFR > 0.5f && pFR < 5.0f) snap.tpmsFR = Math.round(pFR * 10.0f) / 10.0f;
                    if (pRL > 0.5f && pRL < 5.0f) snap.tpmsRL = Math.round(pRL * 10.0f) / 10.0f;
                    if (pRR > 0.5f && pRR < 5.0f) snap.tpmsRR = Math.round(pRR * 10.0f) / 10.0f;

                    snap.tpmsFLWarn = (warnFL != 0);
                    snap.tpmsFRWarn = (warnFR != 0);
                    snap.tpmsRLWarn = (warnRL != 0);
                    snap.tpmsRRWarn = (warnRR != 0);
                }
            }
        } catch (Exception ignored) {}
        finally {
            data66.recycle();
            reply66.recycle();
        }

        // 9. Sunroof & Window Status (Transaction 43 -> df L())
        Parcel data43 = Parcel.obtain();
        Parcel reply43 = Parcel.obtain();
        try {
            data43.writeInterfaceToken(DESCRIPTOR);
            if (mServiceBinder.transact(TRANSACTION_GET_WINDOWS, data43, reply43, 0)) {
                reply43.readException();
                if (reply43.readInt() != 0) {
                    reply43.readInt(); // a: fLWindow
                    reply43.readInt(); // b: fRWindow
                    reply43.readInt(); // c: rLWindow
                    reply43.readInt(); // d: rRWindow
                    int sr = reply43.readInt(); // e: sunroof (2=closed, 1=open)
                    reply43.readInt(); // f: roll
                    int srPos = reply43.readInt(); // g: sunroofPosition (0=closed, >0=open)
                    reply43.readInt(); // h: rollPosition
                    reply43.readInt(); // i: fLPosition
                    reply43.readInt(); // j: fRPosition
                    reply43.readInt(); // k: rLPosition
                    reply43.readInt(); // l: rRPosition
                    int srTilt = reply43.readInt(); // m: sunroofTilt (1=tilted)

                    snap.sunroofPos = srPos;
                    snap.sunroofTilt = srTilt;
                    long nowSr = System.currentTimeMillis();
                    if (srTilt == 1) {
                        mSunroofTilted = true;
                        snap.sunroofState = "tilt";
                    } else if (mSunroofTilted) {
                        if (sr == 2) {
                            mSunroofTiltSawSr2 = true;
                            if (mSunroofTiltBaselinePos < 0 || (nowSr - mSunroofTiltStartMs) < 4000L) {
                                mSunroofTiltBaselinePos = Math.max(mSunroofTiltBaselinePos, srPos);
                            }
                            if ((nowSr - mSunroofTiltStartMs) >= 4000L && mSunroofTiltBaselinePos >= 0 && srPos > mSunroofTiltBaselinePos) {
                                mSunroofTilted = false;
                                snap.sunroofState = "open";
                            } else {
                                snap.sunroofState = "tilt";
                            }
                        } else if (mSunroofTiltSawSr2 || (nowSr - mSunroofTiltStartMs) > 4500L) {
                            mSunroofTilted = false;
                            snap.sunroofState = "close";
                        } else {
                            snap.sunroofState = "tilt";
                        }
                    } else if (sr == 2) { // live car: sr=1 (+pos=9) while physically closed; sr=2 = open/moving
                        snap.sunroofState = "open";
                    } else {
                        snap.sunroofState = "close";
                    }
                }
            }
        } catch (Exception ignored) {}
        finally {
            data43.recycle();
            reply43.recycle();
        }

        // 10. Query 3 Lock Options from CAN bus (cached every 5 seconds unless invalidated)
        try {
            long locksNow = System.currentTimeMillis();
            if (mLastLocksPollMs == 0L || (locksNow - mLastLocksPollMs) >= 5000L) {
                mCachedSpeedLock = getVehicleState(VehicleState.AutomaticLocking);
                mCachedWalkLock = getVehicleState(VehicleState.WALK_LOCK_SWITCH);
                mCachedOffUnlock = getVehicleState(VehicleState.ShutdownAutoUlockSwitch);
                mLastLocksPollMs = locksNow;
            }
            snap.speedLock = mCachedSpeedLock;
            snap.walkLock = mCachedWalkLock;
            snap.offUnlock = mCachedOffUnlock;
        } catch (Exception ignored) {}

        return snap;
    }

    /**
     * Reads the Qinggan system AVM state. This is separate from Android's
     * Camera service because the vehicle cameras are composited by the head unit.
     * Returns -1 when the system policy service is unavailable.
     */
    public int getSystemAvmState() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "qg.system_policy");
            if (binder == null || !binder.isBinderAlive()) return -1;

            data.writeInterfaceToken("com.qinggan.system.ISystemPolicy");
            if (!binder.transact(29, data, reply, 0)) return -1; // ISystemPolicy.getAVMState()
            reply.readException();
            return reply.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void openCamera360() {
        Log.i(TAG, "openCamera360 requested");
        // Direct Binder transact to qg.system_policy (TRANSACTION_show_avm = 20)
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "qg.system_policy");
            if (binder != null && binder.isBinderAlive()) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.qinggan.system.ISystemPolicy");
                    data.writeInt(1); // 1 = show AVM
                    binder.transact(20, data, reply, 0);
                    reply.readException();
                    Log.i(TAG, "openCamera360 direct binder transact succeeded");
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "openCamera360 direct binder failed", t);
        }

        // Fallback: ReverseManager reflection
        try {
            Class<?> rmClass = Class.forName("com.qinggan.system.ReverseManager");
            Object rmInstance = null;
            try {
                Class<?> listenerClass = Class.forName("com.qinggan.common.OnInitListener");
                java.lang.reflect.Method getInstanceWithCtx = rmClass.getMethod("getInstance", Context.class, listenerClass);
                rmInstance = getInstanceWithCtx.invoke(null, mContext != null ? mContext.getApplicationContext() : null, null);
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Method getInstanceNoArgs = rmClass.getMethod("getInstance");
                    rmInstance = getInstanceNoArgs.invoke(null);
                } catch (Throwable t2) {}
            }

            if (rmInstance != null) {
                java.lang.reflect.Method showAVM = rmClass.getMethod("showAVM", int.class);
                showAVM.invoke(rmInstance, 1);
                Log.i(TAG, "openCamera360 ReverseManager.showAVM(1) succeeded");
            }
        } catch (Throwable t) {
            Log.e(TAG, "openCamera360 ReverseManager failed", t);
        }
    }

    private final Handler mWindowPulseHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<String, Runnable> mWindowPulseTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private void handleWindowCommand(final VehicleState state, final int value) {
        if (state == null) return;
        final String stateName = state.name();

        Runnable existing = mWindowPulseTasks.remove(stateName);
        if (existing != null) {
            mWindowPulseHandler.removeCallbacks(existing);
        }

        if (value == 3 || value == 0) {
            setVehicleState(state, 3);
            return;
        }

        setVehicleState(state, value);

        // For non-driver windows (manual on C65), sustain pulses every 250ms for up to 5 seconds
        if (state != VehicleState.DRIVER_WINDOW_CONTROL && (value == 1 || value == 2)) {
            Runnable pulseTask = new Runnable() {
                int count = 0;
                @Override
                public void run() {
                    if (count++ < 20) {
                        setVehicleState(state, value);
                        mWindowPulseHandler.postDelayed(this, 250);
                    } else {
                        mWindowPulseTasks.remove(stateName);
                        setVehicleState(state, 3);
                    }
                }
            };
            mWindowPulseTasks.put(stateName, pulseTask);
            mWindowPulseHandler.postDelayed(pulseTask, 250);
        }
    }

    /**
     * Dispatch UI commands directly to the matching VehicleState or AirConditionState
     */
    public void dispatchCommand(String command, int value) {
        try {
            if ("OPEN_CAMERA".equalsIgnoreCase(command) || "CAMERA".equalsIgnoreCase(command) || "AVM".equalsIgnoreCase(command) || "CAMERA360".equalsIgnoreCase(command)) {
                openCamera360();
                return;
            }
            if ("VEHICLE_TUNK_CONTROL".equalsIgnoreCase(command) || "TRUNK_CONTROL".equalsIgnoreCase(command) || "TAILGATE_CONTROL".equalsIgnoreCase(command)) {
                // Dongfeng Aeolus C65 sedan uses TAILGATE_OPEN_CTRL_REQ (bz.Z -> 0x365 bit 29..31, dataType 67)
                // In C65 0x365 (VehicleSettingComponentImpl.g), 2 = Open request, 1 = Close/Trigger request, 0 = Inactive
                final int reqVal = (value == 2 || value == 1) ? value : 2;
                Log.i(TAG, "Triggering Dongfeng C65 Trunk pulse via TAILGATE_OPEN_CTRL_REQ = " + reqVal);
                setVehicleState(VehicleState.TAILGATE_OPEN_CTRL_REQ, 2);
                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setVehicleState(VehicleState.TAILGATE_OPEN_CTRL_REQ, 1);
                        } catch (Exception ignored) {}
                    }
                }, 220);
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setVehicleState(VehicleState.TAILGATE_OPEN_CTRL_REQ, 0);
                            Log.i(TAG, "Reset TAILGATE_OPEN_CTRL_REQ to 0");
                        } catch (Exception ignored) {}
                    }
                }, 550);
            } else if ("SUNROOF_CONTROL".equalsIgnoreCase(command)) {
                if (value == 1) { // Tilt
                    mSunroofTilted = true;
                    mSunroofTiltStartMs = System.currentTimeMillis();
                    mSunroofTiltSawSr2 = false;
                    mSunroofTiltBaselinePos = -1;
                    setVehicleState(VehicleState.SunroofControl, 1);
                } else if (value == 4 || value == 0) { // Stop
                    setVehicleState(VehicleState.SunroofControl, 4);
                    Handler h = new Handler(Looper.getMainLooper());
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try { setVehicleState(VehicleState.SunroofControl, 0); } catch (Exception ignored) {}
                        }
                    }, 250);
                } else if (value == 3) { // Close
                    mSunroofTilted = false;
                    mSunroofTiltSawSr2 = false;
                    mSunroofTiltBaselinePos = -1;
                    setVehicleState(VehicleState.SunroofControl, 3);
                } else if (value == 2) { // Open
                    mSunroofTilted = false;
                    mSunroofTiltSawSr2 = false;
                    mSunroofTiltBaselinePos = -1;
                    setVehicleState(VehicleState.SunroofControl, 2);
                } else {
                    setVehicleState(VehicleState.SunroofControl, value);
                }
            } else if ("TIRE_PRESSURE_RESET".equalsIgnoreCase(command) || "TPMS_RESET".equalsIgnoreCase(command)) {
                Log.i(TAG, "Triggering C65 TPMS Reset via VehicleState.TIRE_PRESSURE_RESET");
                setVehicleState(VehicleState.TIRE_PRESSURE_RESET, 2);
                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setVehicleState(VehicleState.TIRE_PRESSURE_RESET, 1);
                        } catch (Exception ignored) {}
                    }
                }, 450);
            } else if ("DRIVER_WINDOW_CONTROL".equalsIgnoreCase(command)) {
                handleWindowCommand(VehicleState.DRIVER_WINDOW_CONTROL, value);
            } else if ("PAS_WIDOW_CONTROL".equalsIgnoreCase(command) || "PASSENGER_WINDOW_CONTROL".equalsIgnoreCase(command)) {
                handleWindowCommand(VehicleState.PAS_WIDOW_CONTROL, value);
            } else if ("LEFT_BACK_WINDOW_CONTROL".equalsIgnoreCase(command) || "RL_WINDOW_CONTROL".equalsIgnoreCase(command)) {
                handleWindowCommand(VehicleState.LEFT_BACK_WINDOW_CONTROL, value);
            } else if ("RIGHT_BACK_WINDOW_CONTROL".equalsIgnoreCase(command) || "RR_WINDOW_CONTROL".equalsIgnoreCase(command)) {
                handleWindowCommand(VehicleState.RIGHT_BACK_WINDOW_CONTROL, value);
            } else if ("ALL_WINDOW_CONTROL".equalsIgnoreCase(command)) {
                handleWindowCommand(VehicleState.ALL_WINDOW_CONTROL, value);
            } else if ("AutomaticLocking".equalsIgnoreCase(command)) {
                int canVal = (value == 1 || value == 3) ? 3 : 1; // 3 = ON (10km/h), 1 = OFF
                mCachedSpeedLock = canVal;
                mLastLocksPollMs = 0L;
                setVehicleState(VehicleState.AutomaticLocking, canVal);
                if (mContext != null) {
                    try {
                        android.provider.Settings.System.putInt(mContext.getContentResolver(), "AutomaticLocking", canVal);
                    } catch (Exception ignored) {}
                }
            } else if ("WALK_LOCK_SWITCH".equalsIgnoreCase(command)) {
                int canVal = value != 0 ? 1 : 0;
                mCachedWalkLock = canVal;
                mLastLocksPollMs = 0L;
                setVehicleState(VehicleState.WALK_LOCK_SWITCH, canVal);
                if (mContext != null) {
                    try {
                        android.provider.Settings.System.putInt(mContext.getContentResolver(), "WALK_LOCK_SWITCH", canVal);
                    } catch (Exception ignored) {}
                }
            } else if ("ShutdownAutoUlockSwitch".equalsIgnoreCase(command)) {
                int canVal = value != 0 ? 1 : 0;
                mCachedOffUnlock = canVal;
                mLastLocksPollMs = 0L;
                setVehicleState(VehicleState.ShutdownAutoUlockSwitch, canVal);
                if (mContext != null) {
                    try {
                        android.provider.Settings.System.putInt(mContext.getContentResolver(), "ShutdownAutoUlockSwitch", canVal);
                    } catch (Exception ignored) {}
                }
            } else if ("LOW_BEAM".equalsIgnoreCase(command) || "LIGHTS_CONTROL".equalsIgnoreCase(command) || "DIPPED_BEAM".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.LOW_BEAM, canVal);
            } else if ("HIGH_BEAM".equalsIgnoreCase(command) || "MAIN_BEAM".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.HIGH_BEAM, canVal);
            } else if ("FOG_LIGHT".equalsIgnoreCase(command) || "FRONT_FOG_LIGHT".equalsIgnoreCase(command) || "FOG".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.FOG_LIGHT, canVal);
                setVehicleState(VehicleState.FRONT_FOG_LIGHT, canVal);
            } else if ("PositionLamp".equalsIgnoreCase(command) || "POSITION_LIGHT".equalsIgnoreCase(command) || "PARK_LIGHT".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.PositionLamp, canVal);
                setVehicleState(VehicleState.LITTLE_LIGHT_STATUS, canVal);
                setVehicleState(VehicleState.PARK_LIGHT, canVal);
                // Also trigger dashboard / instrument switch and interior ambient illumination
                setVehicleState(VehicleState.InstrumentSwitchLighting, canVal == 2 ? 1 : 0);
                setVehicleState(VehicleState.DoorAmbientLight, canVal == 2 ? 1 : 0);
                setVehicleState(VehicleState.VEHICLE_AMBIENTLIGHTBRIGHTNESS_SWITCH, canVal == 2 ? 1 : 0);
            } else if ("WARNING_LIGHT".equalsIgnoreCase(command) || "HAZARD".equalsIgnoreCase(command) || "HAZARD_LIGHT".equalsIgnoreCase(command) || "TURN_LIGHTS".equalsIgnoreCase(command) || "TURN_SIGNALS".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.WARNING_LIGHT, canVal);
            } else if ("LEFT_DIRECTION_LIGHT".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.LEFT_DIRECTION_LIGHT, canVal);
                if (canVal == 2) {
                    mLastTurnLeftOnMs = System.currentTimeMillis();
                    mStalkLeft = true;
                    mLastTurnRightOnMs = 0L;
                    mStalkRight = false;
                } else {
                    mLastTurnLeftOnMs = 0L;
                    mStalkLeft = false;
                }
            } else if ("RIGHT_DIRECTION_LIGHT".equalsIgnoreCase(command)) {
                int canVal = (value == 2 || value == 1) ? value : (value != 0 ? 2 : 1);
                setVehicleState(VehicleState.RIGHT_DIRECTION_LIGHT, canVal);
                if (canVal == 2) {
                    mLastTurnRightOnMs = System.currentTimeMillis();
                    mStalkRight = true;
                    mLastTurnLeftOnMs = 0L;
                    mStalkLeft = false;
                } else {
                    mLastTurnRightOnMs = 0L;
                    mStalkRight = false;
                }
            } else if ("AC_SWITCH".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_SWITCH, value);
            } else if ("AC_AUTO".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_AUTO, value);
            } else if ("AC_RECIRC_AIR".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_RECIRC_AIR, value);
            } else if ("AC_FRONT_DEFROST_SWITCH".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_FRONT_DEFROST_SWITCH, value);
            } else if ("AC_REAR_DEFROST_SWITCH".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_REAR_DEFROST_SWITCH, value);
            } else if ("AC_TEMP_DEM".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_TEMP_DEM, value);
            } else if ("AC_BLOWER".equalsIgnoreCase(command)) {
                setAirCondition(AirConditionState.AC_BLOWER, value);
            } else if ("SPORT_MODE_SWITCH".equalsIgnoreCase(command) || "SPORT_MODE".equalsIgnoreCase(command)) {
                setVehicleState(VehicleState.SPORT_MODE_SWITCH, value);
                setVehicleState(VehicleState.MP5_SPORT_MODE_SWITCH, value);
            } else if ("DRIVING_MODE".equalsIgnoreCase(command)) {
                setVehicleState(VehicleState.DRIVING_MODE, value);
            } else {
                Log.d(TAG, "Unknown CAN command dispatched: " + command);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch CAN command: " + command, e);
        }
    }
}
