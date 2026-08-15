# greenrobot EventBus (pulled in by the Onyx SDK) locates @Subscribe methods by
# reflection. EventBus ships as a plain jar with no embedded rules, so without these a
# minifying consumer strips the anonymous subscriber's methods and TouchHelper.register
# throws "no @Subscribe methods" — the palm gate's pen-proximity feed would be lost.
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
