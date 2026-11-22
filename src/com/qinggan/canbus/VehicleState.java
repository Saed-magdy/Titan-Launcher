package com.qinggan.canbus;

import android.os.Parcel;
import android.os.Parcelable;
/* loaded from: classes.dex */
public enum VehicleState implements Parcelable {
    ASR(39),
    TyresSpeedWarning(40),
    TyresSpeed(41),
    TyrePressureMonitoring(42),
    AdaptiveCruiseControlSystem(43),
    DriverAlertSystem(44),
    LastVehicleDistance(45),
    FrontAssistSet(46),
    PrevWarning(47),
    DisplayDistanceWarning(48),
    LaneKeepingAssist(49),
    TravelProgramme(50),
    VehicleDistance(51),
    ParkingAutoActive(52),
    ParkingFrontVolume(53),
    ParkingFrontTone(54),
    ParkingBackVolume(55),
    ParkingBackTone(56),
    LightOpenedTime(58),
    AutomaticHeadlightControl(59),
    LaneChangeFlash(60),
    InstrumentSwitchLighting(61),
    ComingHomeFunction(62),
    LeavingHomeFunction(63),
    TravelMode(64),
    DoorAmbientLight(65),
    FootWellLight(66),
    DynamicBigLight(67),
    DynamicBigLightAssistance(68),
    SynchronousAdjustment(69),
    LowerWhileReversing(70),
    AutomaticWipingInRain(71),
    RearWindowWipingInReverseGear(72),
    ParkingOverlapMirror(73),
    ConvenienceOpening(74),
    DoorUnlocking(75),
    SpeedLimit(76),
    AutomaticLocking(76),
    FCWSensitivity(77),
    TSRSwitch(78),
    AutoWelcomeLampSwitch(79),
    AutoWindUnlockSet(80),
    NaviTurn(81),
    PositionLamp(82),
    AutoWindKeyPressSet(83),
    AutoWindLockSet(84),
    MP5ProfileSet(85),
    AC_HAZE_MODE(86),
    AC_ONE_BUTTON_WARMTH_MODE(87),
    AC_RAPID_COOLING_MODE(88),
    AC_RAIN_SNOW_MODE(89),
    AC_BABY_CARE_MODE(90),
    AC_ROMANTIC_MODE(91),
    AC_SMOKING_MODE(92),
    AC_STOP_CAR_MODE(93),
    DaytimeRunninglights(116),
    RKELockFeedbackSwitch(124),
    RKEFunction(125),
    ShutdownAutoUlockFunction(126),
    ShutdownAutoUlockSwitch(127),
    AutoCloseWindowsFunction(128),
    AutoCloseWindowsMode(129),
    PositionLightAutoCloseFunction(130),
    PositionLightAutoCloseSwitch(131),
    RVMAutoFoldingSwitch(132),
    RVMFunction(133),
    RainingAutoCloseWindowsFunction(134),
    RainingAutoCloseWindowsSwitch(135),
    SunroofFunction(136),
    SunroofControl(137),
    LDWFunction(138),
    LDWSwitch(151),
    LDWStatus(139),
    LDWWaringValue(140),
    IHBCFunction(141),
    IHBCSwitch(152),
    IHBCStatus(142),
    FCWFunction(143),
    FCWSwitch(144),
    AEBFunction(145),
    AEBSwitch(146),
    EPSFunction(147),
    EPSModeSelection(148),
    SendDateTimetoICUFunction(149),
    SendDateTimetoICUSwitch(150),
    STEERING_WHEEL_HEATING_SWITCH(151),
    LEFT_SEAT_VALICATION_LEVEL(152),
    RIGHT_SEAT_VALICATION_LEVEL(153),
    CLEAN_AIR_ACTIVE_SWITCH(154),
    CLEAN_AIR_LEVEL(155),
    OPS_MUTE_SWITCH(156),
    OPS_SURFACE_POP_SWITCH(157),
    OPS_DRAG_CAR_CONNECTION_SWITCH(158),
    RDK_CALIBRATION_TYRE_SWITCH(159),
    HEAD_LIGHT_STATUS(160),
    RVC_REVER_LIGHT_SWITCH(161),
    STEERING_WHEEL_HEATING_SWITCH_OPERABILITY(162),
    HEAD_LIGHT_STATUS_OPERABILITY(163),
    RADAR_PRODE_COUNT(164),
    OPS_MUTE_SWITCH_OPERABILITY(165),
    DAY_LIGHT_SWITCH(166),
    SUNROOF_RKESETTING_SWITCH(167),
    SUNROOFROLLSHADE_CONTROLSELECT(168),
    WINDOW_VENTILATE_RKESETTING(169),
    USER_DET_SEND_SWITCH(170),
    OBC_HOUR_START_SETTING(171),
    OBC_MINUTE_START_SETTING(172),
    OBC_HOUR_END_SETTING(173),
    OBC_MINUTE_END_SETTING(174),
    OBC_CANCEL_SIGNAL_SWITCH(175),
    OBC_SET_SIGNAL_SWITCH(176),
    USER_REMOTE_BOOT_FEED_SWITCH(177),
    LOW_POWER_SET_SWITCH(178),
    BMS_BATT_SOC_STATUS(179),
    SAFETY_TONE_SWITCH(180),
    PARKING_ASSISTANCE_SWITCH(181),
    ENERGY_RECOVERY_GEAR(182),
    DOOR_OPENING_TONE_SWITCH(183),
    ENERGY_CONSUM_HISTORY(179),
    ENERGY_CON_SUM_AV(180),
    ENERGY_FLOW(181),
    BSD_SYSTEM_SWITCH(182),
    BSD_OP_Status(183),
    TIME_CHARGING_SWITCH(184),
    LDW_WARNING_TYPE(185),
    LOW_POWER_TONE_SWITCH(186),
    MCU_ALARM_LEVEL_STATUS(187),
    SYS_LANGUAGE(188),
    SIGNAL_PEDL_SWITCH(189),
    WARM_SWITCH(190),
    VEHICLE_DOOR_CONTROL(191),
    VEHICLE_IGNITION_SWITCH(192),
    SIGNAL_PEDL_STATUS(193),
    WARM_STATUS(194),
    LKS_LANE_AID_TYPE_REQ(195),
    LDW_LKS_SENSITIVITY(196),
    PGS_SWITCH(197),
    PEDE_PROT_SWITCH(198),
    HAM_REQ_SWITCH(199),
    TSR_REQ_SWITCH(200),
    LKSStatus(201),
    TSRStatus(202),
    DRIVER_WINDOW_CONTROL(203),
    PAS_WIDOW_CONTROL(204),
    LEFT_BACK_WINDOW_CONTROL(205),
    RIGHT_BACK_WINDOW_CONTROL(206),
    ALL_WINDOW_CONTROL(207),
    ALL_LEFT_WINDOW_CONTROL(208),
    ALL_RIGHT_WINDOW_CONTROL(209),
    ALL_FRONT_WINDOW_CONTROL(210),
    ALL_REAR_WINDOW_CONTROL(211),
    SUNROOF_TITL_CONTROL(212),
    VEHICLE_TUNK_CONTROL(213),
    HIGH_BEAM(214),
    LOW_BEAM(215),
    PARK_LIGHT(216),
    FRONT_FOG_LIGHT(217),
    REAR_FOG_LIGHT(218),
    RIGHT_DIRECTION_LIGHT(219),
    LEFT_DIRECTION_LIGHT(220),
    FRONT_WASHER_WIPER(221),
    FRONT_WASHER_WIPER_STATUS(265),
    REAR_WINDOWN_HEAT_STATUS(266),
    REAR_WASHER_WIPER(222),
    ATMOSPHERE_LAMP_GRADS(223),
    AVN_KEY_REARDEF(224),
    FRONT_WIPER(225),
    MP5_WORK_SWITCH(227),
    TAILGATE_DELAY_COLSE_TIMING(228),
    IC_STYLE_WITCH(229),
    KICK_FUNCTION_ENABLE_SWITCH(230),
    IC_STYLE_SETTING_LEFT(231),
    IC_STYLE_SETTING_RIGHT(232),
    ELECTRIC_TAILGATE_SWITCH(233),
    WALK_LOCK_SWITCH(234),
    HDC_SWITCH(235),
    SEAT_WELCOM_SWITCH(321),
    MAIN_SCREEN_BRIGHTNESS(236),
    THEME_CHANGE(237),
    VEHICLE_AMBIENTLIGHTBRIGHTNESS_SWITCH(238),
    VEHICLE_AMBIENTLIGHTBRIGHTNESS_COLOR(239),
    VEHICLE_AMBIENTLIGHTBRIGHTNESS_LEVEL(240),
    FOLLOW_ME_HOME_SETTING(241),
    PM2_5_SWITCH(242),
    TAILGATEDOORLOCK_ENABLE_SWITCH(243),
    BCMDOORLOCK(244),
    ADVANCEDPARKMODE(245),
    ADVANCEDPARKASSIST(246),
    ADV_REQUSETDISPLY(247),
    GW_ESC_VEHSPDVALIDFLAG(248),
    ESC_ABSALARMSIG(249),
    GW_ESC_VEHSPD(250),
    DEVICE_POWER(251),
    GEARSHIFTREMIND_FC_ENABLE_SWITCH(252),
    VOICE_CONTROL_SUNROOF_SWITCH(253),
    DRIVING_LOKING_FC_SWITCH(254),
    TRUNK_AUTO_LOKING_FC_SWITCH(255),
    LOCK_FAIL_SOUND_SWITCH(256),
    LOCK_SUCCESS_SOUND_SWITCH(257),
    LDW_ENABLE_STAUS(258),
    HMA_STATUS(259),
    FRONT_SEAT_HEATING_COMMAND_RIGHT(260),
    FRONT_SEAT_HEATING_COMMAND_LEFT(261),
    FRONT_SEAT_VENTILATION_COMMAND_RIGHT(262),
    FRONT_SEAT_VENTILATION_COMMAND_LEFT(263),
    TSR_WARNING_TYPE(264),
    INSTRUMENTS_BRIGHTNESS_MODE_DAY(265),
    INSTRUMENTS_BRIGHTNESS_MODE_NIGHT(266),
    TIRE_PRESSURE_RESET(267),
    NAVI_SPEED_LIMIT_MILE(268),
    NAVI_SPEED_LIMIT_KM(269),
    BMS_VEH_MODE(270),
    BMS_OBC_REM_TIME(271),
    SIDEVIEW_CONTROL(272),
    CHARGE_RATE(274),
    ENERGY_FLOW_LEVEL(275),
    BATT_OBC_TIME(276),
    TSR_SWITCH(277),
    HMA_ON_OFF_REQ(278),
    TSR_OPERATING_STATUS(279),
    TSR_SPEED_LIMIT(280),
    TSR_SIGN_TYPE(281),
    TSR_SPEED_LIMIT_UNIT(282),
    TSR_WARNING(283),
    TSR_SWITCH_RESPONSE(285),
    FCW_STATUS(286),
    AEB_STATUS(287),
    PEDE_PROT_STATUS(288),
    FCW_SWITCH(289),
    AEB_SWITCH(290),
    ESC_OFF_STATUS(319),
    READY_LAMP(296),
    ONE_KM_FLAG(273),
    BCM_RCM_ERROR(291),
    COMBINATION_LIGHT_SWITCH(292),
    ALL_SEAT_HEATING_SWITCH(293),
    ALL_SEAT_VENTILATION_SWITCH(294),
    FRONT_SEAT_HEATING_BATTERY_STATUS(295),
    AVM_CALIBRATE_START(296),
    PHYSICAL_KEY_LIGHT_CONTROL_STATUS(297),
    PHYSICAL_KEY_FRONT_WIPER_CONTROL_STATUS(298),
    PHYSICAL_KEY_FRONT_WASHER_CONTROL_STATUS(299),
    PHYSICAL_KEY_REAR_WASHER_CONTROL_STATUS(300),
    PHYSICAL_KEY_HIGH_LIGHT_CONTROL_STATUS(301),
    LITTLE_LIGHT_STATUS(302),
    TAILGATE_OPEN_CTRL_REQ(303),
    TAILGATE_COLSE_CTRL_REQ(304),
    SEAT_MEMORY_SET_REQ(305),
    SEAT_MEMORY_USE_REQ(306),
    SUNSHADING_CURTAIN_REQ(307),
    FOG_LIGHT(308),
    WARNING_LIGHT(309),
    ADAPTIVE_CRUISE_CONTROL_SWITCH(310),
    ADAPTIVE_CRUISE_CONTROL_SIGNAL_STATUS(311),
    CANBUS_LOG_ENABLE(312),
    DVR_PASSWORD_RESET(313),
    IACC_OR_ACC_FUNCTION_SWITCH(314),
    IACC_OR_ACC_STATUS(315),
    COMBINATION_VEHICLE_REQ1(316),
    COMBINATION_VEHICLE_REQ2(317),
    COMBINATION_VEHICLE_REQ3(318),
    MPU_SET_WEATHER(320),
    FLK_SYSTEM_SWITCH(323),
    VEHICLE_RESET(322),
    SEAT_POSITION_CHANGE(324),
    SET_SEAT_USERACCOUNT(325),
    ECO_SWITCH(326),
    SPORT_MODE_SWITCH(327),
    SEAT_MASSAGE_MODE_REQ(328),
    SEAT_MASSAGE_INTENSITY_REQ(329),
    DRIVING_MODE_MEMORY_SWITCH(330),
    SEAT_VENTILATION_AUTO_OPEN_SWITCH(331),
    SEAT_MASSAGE_KEY_FEEDBACK(332),
    TREND_WELCOME_UNLOCK_WITCH(333),
    TREND_WELCOME_LAMP_SWITCH(334),
    APA_SWITCH(335),
    SEAT_VENTILATE_AUTO_SWITCH(336),
    ISA_MODE(337),
    LDW_WARNING_MODE(338),
    AIR_OUTLET_MAX_REQ(339),
    AIR_DRIVER_SEAT_OUTLET_REQ(340),
    AIR_PASSENGER_SEAT_OUTLET_REQ(341),
    AIR_DRIVER_SEAT_REM_REQ(342),
    AIR_PASSENGER_SEAT_REM_REQ(343),
    AIR_LEFT_LR_POS_REQ(344),
    AIR_LEFT_UD_POS_REQ(345),
    AIR_LEFT_MID_LR_POS_REQ(346),
    AIR_LEFT_MID_UD_POS_REQ(347),
    AIR_RIGHT_MID_LR_POS_REQ(348),
    AIR_RIGHT_MID_UD_POS_REQ(349),
    AIR_RIGHT_LR_POS_REQ(350),
    AIR_RIGHT_UD_POS_REQ(351),
    MFTS_AC_MAX_AC(352),
    MFTS_AC_OUTLET_AUTO(353),
    ADP_POPUP_WINDOW_REQUEST(354),
    MASSAGE_SAFE_MODE_SWITCH(355),
    AVAS_PAUSE_SWITCH(356),
    UPA_SWITCH(366),
    OTA_MODE_STATUS(367),
    BSD_SOUND_SWITCH(368),
    ADAS_SEG_EFFSPLMT(369),
    ANTI_FORGREMIND_SWITCH(370),
    ADP_INSTANT_WARMING_MODEST(371),
    ICA_STATUS(372),
    LKA_SWITCH_STATE(373),
    IHBC_SWITCH(374),
    KICK_FUNCTION_STATE(375),
    ELECTRIC_TAILGATE_FUNCTION_STATE(376),
    TAILGATE_POSITION(377),
    LKA_MODE(378),
    LKA_MAIN_SW(379),
    ISA_MAIN_SW(380),
    FCW_SENSITIVITY(381),
    CWP_SWITCH(382),
    MP5_VBC_MODE(383),
    MP5_BBINCAR_MODE(384),
    MP5_MUTE(385),
    MP5_SURROUND_MODE(386),
    CWP_OPERATING_STATUS(387),
    CWP_FAILURE_STATUS(388),
    CWP_RD_OPERATING_STATUS(389),
    CWP_RD_DEVICE_TYPE(390),
    CWP_RD_FORGOTTEN_REMINDER(391),
    BSD_CALIBRATE(392),
    BSD_CALIBRATE_RESULT_LEFT(393),
    BSD_CALIBRATE_RESULT_RIGHT(394),
    MP5_REQ_AVM(395),
    AVM_LOG_STATUS(396),
    AVM_FAULT(397),
    MP5_DMS_SYSTEM_ENABLE(398),
    MP5_DMS_EYEFATIGUE_MONITOR(399),
    MP5_DMS_SENSITY(400),
    MP5_DMS_MONITOR_LONGTIME_DRIVE(401),
    MP5_DMS_MONITOR_SIDE_VIEW(402),
    MP5_DMS_MONITOR_USING_PHONE(403),
    MP5_DMS_MONITOR_SOMKING(404),
    MP5_DMS_MONITOR_DRINK_WATER(405),
    MP5_DMS_USERACCOUNT(406),
    MP5_BSD_LCW_SWITCH(407),
    MP5_JA_SWITCH(408),
    MP5_FCTA_SWITCH(409),
    MP5_TLC_MODE(410),
    MP5_LSS_MODE(411),
    MP5_DOW_SWITCH(412),
    MP5_RCTA_SWITCH(413),
    MP5_MULTIMODE_SWITCH(414),
    MP5_ESA_SWITCH(415),
    ENM_LKAS_OPERATING_STATUS(416),
    ENM_PARKING_ASSIST_STATUS(417),
    ENM_PARKING_ASSIST_PROGRESS(418),
    ENM_PARKING_ASSIST_PROGRESS_STATUS(419),
    ENM_PARKING_ASSIST_INFORMATION_DISPLAY_REQUEST(420),
    HOPE_OPEN_RAIN_SNOW_MODE(421),
    AVM_DYNAMIC_GUIDES_SET_REQUEST(422),
    AVM_VEHICLE_COLOR_SET_REQUEST(423),
    AVM_OPERATING_MODE_CONTROL_SET_REQUEST(424),
    AVM_TRUN_TRIGGER_SET_REQUEST(425),
    FIRST_START_TO_SPRING_AVM_SET_REQUEST(426),
    AVM_SYSTEM_ERROR_STATUS(427),
    AVM_VIEW_MODE_SET_STATUS(428),
    ENM_PARKING_ASSIST_INFORMATION_DISPLAY_STATUS(429),
    ENM_PARK_ASSISTANT_SOUND_CADENCE_COMMAND(430),
    MP5_INTELLIGENT_SWITCH(431),
    MP5_INTELLIGENT_MODE(432),
    MP5_INTELLIGENT_TORQUE_COEF(433),
    ECU_INTELLIGENT_TORQUE_COEF(434),
    DRIVING_MODE(435),
    MP5_LOCK_CMD_WINDOW_SWITCH(436),
    MP5_SUNSHAD_CUR_CLOSE_LOCK_SWITCH(437),
    DRIVING_MODE_STATE(438),
    VEHICLE_AMBIENTLIGHTBRIGHTNESS_LEVEL_NIGHT(439),
    MP5_SELECTED_EPS_MODE(440),
    EMS_SELECTED_EPS_MODE(441),
    MP5_UNLOCK_LAMP_PATTERN_SWITCH(442),
    MP5_LOCK_LAMP_PATTERN_SWITCH(443),
    MP5_PARKING_SAYHI_PATTERN_SWITCH(444),
    MP5_ASD_SWITCH(445),
    MP5_ASD_MODE(446),
    AUTO_HOLD(447),
    MP5_SPORT_MODE_SWITCH(448),
    MP5_AL_RELATED_MUSIC_SWITCH(449),
    MFTS_AC_KEY_AUTO(450),
    MFTS_AC_KEY_OFF(451),
    MFTS_AC_KEY_DEF_MAX(452),
    MFTS_AC_KEY_RDEF(453),
    MFTS_AC_KEY_OUTLET_AUTO(454),
    MP5_AL_WELCOME_SWITCH(455),
    MP5_COURTESY_LIGHT_SWTICH(456),
    SENSING_TAILGATE_FUNCTION(457),
    MP5_RFOGLIGHT_SWITCH(458),
    IVI_MAINLIGHT_SWITCH(459),
    IVI_LREAR_MIRRORXY_ADJUST_SW(460),
    IVI_RREAR_MIRRORXY_ADJUST_SW(461),
    IVI_REAR_MIRROR_ADJUST_ACTISW(462),
    IVI_SELECTED_DRIVING_MODE(463),
    MP5_DAB_SWITCH(464),
    IVI_IGNOnOffReq(465),
    MP5_TAILGATEHEIGHT(466),
    FRLOWBEAMADJSW(467),
    WELCOME_UNLOCK_WITCH(468),
    EMS_CREDIT_VHE_LIMP_ST(469),
    FORBID_VHE_START_EN_ST(470),
    HOT_MAX_AC(471),
    IVI_EPB_SW(472),
    ESC_EPB_MESSAGE_REQ(473),
    EPEDAL_SW(474),
    RLS_AUTO_LIGHT(475),
    AUTOINTWIND(476),
    WIPER_MAINTAIN_ST(477),
    SOC_CALCULATE(478),
    DOMELIGHT_TOGE(479),
    CRUISRANGE_DISPLAY(480),
    RESERVED_CHRGSOC_TARGET(481),
    BATTERY_TYPE(482),
    IMMEDIATE_CHRG_REQ(483),
    RESERVED_CHRG_STOPREQ(484),
    CHRGCURRENT_SET(485),
    GUNINSERT_KEEPWARM_SETSW(486),
    DISCHRGST_V2L(487),
    DISCHRG_SOCTARGET(488),
    BATTSOC_STATUS(489),
    MP5_SERCHCAR(490),
    MP5_SERCHCAR_VOICE(491),
    MP5_UNLOCKSW(492),
    MP5_UNLOCK_VOICE(493),
    MP5_LOCKSW(494),
    MP5_LOCK_VOICE(495),
    MP5_SAYHI(496),
    MP5_SAYHI_VOICE(497),
    MP5_ACOUSTICS_STOP_REQ(498),
    PDCU_CHRG_CNCTST(499),
    PDCU_DISCHRG_CNCTST_V2L(500),
    IVI_TRACTIONMODEDISPLAYREQ(501),
    IVI_TRACTIONREQ(502),
    PDCU_TRACTIONALARM(503),
    PDCU_TRACTIONREQFB(504),
    PDCU_TRACTIONMODEDISPLAYREQFB(505),
    PDCU_CHRGST(506),
    IVI_CHARGGUNUNLOCKREQ(507),
    MP5_DISCHRGSTOPREQ(508),
    DRIVER_BELTSWITCH(509),
    MP5_SEAT_REST_MODE(510),
    MP5_SEAT_ADJ_POPUP(511),
    AC_REST_MODE(512);
    
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_1 = 1;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_10 = 10;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_11 = 11;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_12 = 12;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_13 = 13;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_14 = 14;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_15 = 15;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_16 = 16;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_17 = 17;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_18 = 18;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_19 = 19;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_2 = 2;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_20 = 20;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_21 = 21;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_22 = 22;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_23 = 23;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_24 = 24;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_25 = 25;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_26 = 26;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_27 = 27;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_28 = 28;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_29 = 29;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_3 = 3;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_30 = 30;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_31 = 31;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_32 = 32;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_33 = 33;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_34 = 34;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_35 = 35;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_36 = 36;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_4 = 4;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_5 = 5;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_6 = 6;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_7 = 7;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_8 = 8;
    public static final int AMBIENTLIGHTBRIGHTNESS_COLOR_9 = 9;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_1 = 1;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_10 = 10;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_2 = 2;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_3 = 3;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_4 = 4;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_5 = 5;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_6 = 6;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_7 = 7;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_8 = 8;
    public static final int AMBIENTLIGHTBRIGHTNESS_LEVEL_9 = 9;
    public static final int AVM_COLOR_BLACK = 1;
    public static final int AVM_COLOR_BLUE = 6;
    public static final int AVM_COLOR_BROWN = 5;
    public static final int AVM_COLOR_RED = 2;
    public static final int AVM_COLOR_SILVERY = 4;
    public static final int AVM_COLOR_WHITE = 3;
    public static final int AVM_ENTER_CALIBRATION = 3;
    public static final int AVM_EXIT = 7;
    public static final int AVM_FRONT_BIRDVIEW_REQ = 2;
    public static final int AVM_FRONT_WIDE_ANGLE_REQ = 1;
    public static final int AVM_LEFT_BIRDVIEW = 6;
    public static final int AVM_LEFT_FRONT_AND_RIGHT_FRONT_BIRDVIEW_REQ = 3;
    public static final int AVM_OPEN = 1;
    public static final int AVM_REAR_BIRDVIEW_REQ = 5;
    public static final int AVM_REAR_WIDE_ANGLE_REQ = 4;
    public static final int AVM_RIGHT_BIRDVIEW = 7;
    public static final int AVM_SYSTEM_ERROR = 1;
    public static final int AVM_SYSTEM_NORMAL = 0;
    public static final int AVM_UPDATE = 4;
    public static final int AutoCloseWindowsMode_BY_LOCKDOOR = 1;
    public static final int AutoCloseWindowsMode_BY_RKE = 0;
    public static final Parcelable.Creator<VehicleState> CREATOR = new Parcelable.Creator<VehicleState>() { // from class: com.qinggan.canbus.VehicleState.1
        @Override // android.os.Parcelable.Creator
        /* renamed from: a */
        public VehicleState createFromParcel(Parcel source) {
            VehicleState state = VehicleState.values()[source.readInt()];
            state.value = source.readInt();
            return state;
        }

        @Override // android.os.Parcelable.Creator
        /* renamed from: a */
        public VehicleState[] newArray(int size) {
            return new VehicleState[size];
        }
    };
    public static final int CUTOFF_UNLOCK_DOORS_ALL = 2;
    public static final int CUTOFF_UNLOCK_DOORS_FL = 1;
    public static final int CUTOFF_UNLOCK_DOORS_INACTIVE = 0;
    public static final int DRIVING_MODE_NORMAL = 0;
    public static final int DRIVING_MODE_SPORT = 1;
    public static final int DRIVING_MODE_WINTER = 3;
    public static final int ECU_INTELLIGENT_DRIVER = 1;
    public static final int ECU_INTELLIGENT_MANUAL = 2;
    public static final int ECU_INTELLIGENT_SAVING_FUEL = 3;
    public static final int ECU_TORQUE_COEF_1 = 1;
    public static final int ECU_TORQUE_COEF_2 = 2;
    public static final int ECU_TORQUE_COEF_3 = 3;
    public static final int ECU_TORQUE_COEF_4 = 4;
    public static final int ECU_TORQUE_COEF_5 = 5;
    public static final int ECU_TORQUE_COEF_6 = 6;
    public static final int ECU_TORQUE_COEF_7 = 7;
    public static final int ECU_TORQUE_COEF_8 = 8;
    public static final int EMS_EPS_MODE_ECO = 2;
    public static final int EMS_EPS_MODE_SPORT = 1;
    public static final int EPS_MODE_COMFORT = 1;
    public static final int EPS_MODE_RESERVED = 0;
    public static final int EPS_MODE_RESERVED1 = 3;
    public static final int EPS_MODE_SPORT = 2;
    public static final int FOLLOW_ME_HOME_SETTING_INACTIVE = 0;
    public static final int FOLLOW_ME_HOME_SETTING_OFF = 1;
    public static final int FOLLOW_ME_HOME_SETTING_TIME_10S = 2;
    public static final int FOLLOW_ME_HOME_SETTING_TIME_120S = 6;
    public static final int FOLLOW_ME_HOME_SETTING_TIME_20S = 3;
    public static final int FOLLOW_ME_HOME_SETTING_TIME_30S = 4;
    public static final int FOLLOW_ME_HOME_SETTING_TIME_60S = 5;
    public static final int FRONT_OFF_REAE_ON = 2;
    public static final int FRONT_ON_REAE_OFF = 3;
    public static final int FRONT_REAE_OFF = 1;
    public static final int FRONT_REAE_ON = 4;
    public static final int FUNCTION_AVAILABLE = 1;
    public static final int FUNCTION_ERROR = 2;
    public static final int FUNCTION_UNAVAILABLE = 0;
    public static final int IC_STYLE_WITCH_CLASSIC = 1;
    public static final int IC_STYLE_WITCH_COMFORTABLE = 2;
    public static final int IC_STYLE_WITCH_DYNAMIC = 3;
    public static final int IHBC_ACTIVE = 1;
    public static final int IHBC_BLINDNESS = 4;
    public static final int IHBC_OFF = 5;
    public static final int IHBC_PASSIVE = 0;
    public static final int IHBC_PERMANENT_ERROR = 3;
    public static final int IHBC_TERMPORATY_UNAVAILABLE = 2;
    public static final int IPK_CHINESE = 1;
    public static final int IPK_ENGLISH = 2;
    public static final int LAMP_PATTERN_BUILDING_BLOCKS = 5;
    public static final int LAMP_PATTERN_CLASSIC = 2;
    public static final int LAMP_PATTERN_METEOR = 4;
    public static final int LAMP_PATTERN_OFF = 1;
    public static final int LAMP_PATTERN_PULSATION = 3;
    public static final int LDW_ACTIVE = 2;
    public static final int LDW_BLINDNESS = 5;
    public static final int LDW_LANE_ASSIST_TYPE = 1;
    public static final int LDW_OFF = 0;
    public static final int LDW_OFF_TYPE = 3;
    public static final int LDW_PERMANENT_ERROR = 4;
    public static final int LDW_STANDBY = 1;
    public static final int LDW_TERMPORATY_UNAVAILABLE = 3;
    public static final int LDW_UI_AND_SPEAKER_WARNING_TYPE = 2;
    public static final int LDW_UI_WARNING_TYPE = 1;
    public static final int LDW_WARING_VALUE_LONG = 2;
    public static final int LDW_WARING_VALUE_NORMAL = 0;
    public static final int LDW_WARING_VALUE_RESERVED = 3;
    public static final int LDW_WARING_VALUE_SHORT = 1;
    public static final int LKS_LANE_ASSIST_TYPE = 3;
    public static final int LKS_SENSITIVITY_HIGH = 2;
    public static final int LKS_SENSITIVITY_LOW = 1;
    public static final int LOCK_CMD_WINDOW_CLOSE = 1;
    public static final int LOCK_CMD_WINDOW_COCKED = 3;
    public static final int LOCK_CMD_WINDOW_OPEN = 2;
    public static final int MODULE_CONTROL_CLOSE = 1;
    public static final int MODULE_CONTROL_DEFAULT = 0;
    public static final int MODULE_CONTROL_OPEN = 2;
    public static final int MODULE_CONTROL_RESERVED = 3;
    public static final int MP5_ASD_MODE_CLASSIC = 1;
    public static final int MP5_ASD_MODE_SURGING = 2;
    public static final int MP5_ASD_MODE_WILD = 3;
    public static final int MP5_ASD_SWITCH_LINK_SPORT = 3;
    public static final int MP5_ASD_SWITCH_OFF = 1;
    public static final int MP5_ASD_SWITCH_OPEN = 2;
    public static final int MP5_EPS_MODE_ADAPTION = 0;
    public static final int MP5_EPS_MODE_COMFORT = 1;
    public static final int MP5_EPS_MODE_SPORT = 2;
    public static final int NO_LANE_ASSIST_TYPE = 0;
    public static final int RDP_LANE_ASSIST_TYPE = 2;
    public static final int RKELOCKFEEDBACKSWITCHCLOSE = 1;
    public static final int RKELOCKFEEDBACKSWITCHOPEN = 0;
    public static final int SUNSHAD_CUR_LOCK_CLOSE = 1;
    public static final int SUNSHAD_CUR_LOCK_OPEN = 2;
    public static final int SWITCH_CLOSE = 0;
    public static final int SWITCH_OPEN = 1;
    public static final int SYS_LANGUAGE_0 = 0;
    public static final int SYS_LANGUAGE_1 = 1;
    public static final int SYS_LANGUAGE_2 = 2;
    public static final int SYS_LANGUAGE_3 = 3;
    public static final int SYS_LANGUAGE_4 = 4;
    public static final int SYS_LANGUAGE_5 = 5;
    public static final int SYS_LANGUAGE_6 = 6;
    public static final int SYS_LANGUAGE_7 = 7;
    public static final int VALICATION_LEVEL_1 = 1;
    public static final int VALICATION_LEVEL_2 = 2;
    public static final int VALICATION_LEVEL_3 = 3;
    public static final int VALICATION_LEVEL_OFF = 0;
    public static final int VEHICLE_ENERGY_FLOW_DRIVER_STATUS = 2;
    public static final int VEHICLE_ENERGY_FLOW_FAST_CHARGING_STATUS = 4;
    public static final int VEHICLE_ENERGY_FLOW_PENDING_STATUS = 1;
    public static final int VEHICLE_ENERGY_FLOW_RECOVERY_STATUS = 3;
    public static final int VEHICLE_ENERGY_FLOW_SLOW_CHARGING_STATUS = 5;
    public static final int VEHICLE_ENERGY_FLOW_STOP_STATUS = 0;
    public static final int VSPEED_LOCKING_10KMh = 3;
    public static final int VSPEED_LOCKING_15KMh = 4;
    public static final int VSPEED_LOCKING_20KMh = 5;
    public static final int VSPEED_LOCKING_5KMh = 2;
    public static final int VSPEED_LOCKING_INACTIVE = 0;
    public static final int VSPEED_LOCKING_OFF = 1;
    public static final int WIPER_HIGH = 2;
    public static final int WIPER_LOW = 1;
    public static final int WIPER_MIDDLE = 3;
    public static final int WIPER_OFF = 0;
    private int value;

    VehicleState(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(ordinal());
        dest.writeInt(this.value);
    }
}
