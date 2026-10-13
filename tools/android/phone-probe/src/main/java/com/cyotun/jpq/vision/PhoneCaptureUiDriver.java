package com.cyotun.jpq.vision;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Explicit stopped-capture preflight. Only our start button and its standard
 * system consent are controlled. The game is never navigated or clicked. */
public final class PhoneCaptureUiDriver extends Instrumentation {
    private boolean enabled;
    private boolean stopping;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        stopping=arguments!=null && "true".equals(arguments.getString("stopCaptureProbe"));
        enabled=stopping || (arguments!=null && "true".equals(arguments.getString("startCaptureProbe")));
        start();
    }
    @Override public void onStart() {
        boolean clickedStart=false, clickedConsent=false;
        String result="explicit_probe_required";
        if(enabled) {
            long deadline=SystemClock.elapsedRealtime()+15000;
            try {
                while(SystemClock.elapsedRealtime()<deadline) {
                    AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                    if(root!=null) {
                        String pkg=String.valueOf(root.getPackageName());
                        if("com.cyotun.jpq".equals(pkg) || "com.android.systemui".equals(pkg)) {
                            List<AccessibilityNodeInfo> nodes=new ArrayList<>(); flatten(root,nodes);
                            if("com.cyotun.jpq".equals(pkg) && !clickedStart) {
                                for(AccessibilityNodeInfo node:nodes) {
                                    if((stopping ? "停止记牌" : "开始记牌").contentEquals(node.getText()==null ? "" : node.getText())) {
                                        clickedStart=click(node); break;
                                    }
                                }
                                if(stopping && clickedStart) { clickedConsent=true; break; }
                            } else if("com.android.systemui".equals(pkg) && clickedStart) {
                                boolean ownConsent=false;
                                for(AccessibilityNodeInfo node:nodes)
                                    if(node.getText()!=null && node.getText().toString().contains("记牌器")) ownConsent=true;
                                if(ownConsent) for(AccessibilityNodeInfo node:nodes) {
                                    String label=String.valueOf(node.getText());
                                    if("共享屏幕".equals(label) || "立即开始".equals(label) || "开始录制".equals(label)) {
                                        clickedConsent=click(node); break;
                                    }
                                }
                                if(clickedConsent) break;
                            }
                        }
                    }
                    SystemClock.sleep(150);
                }
                result=clickedConsent ? (stopping ? "stop_button_clicked_check_service" : "standard_consent_clicked_check_service_and_projection") : "no_requested_button_clicked";
            } catch(Exception error) { result="driver_failed:"+error.getClass().getSimpleName(); }
        }
        Bundle out=new Bundle(); out.putString("result",result);
        out.putBoolean("clickedStart",clickedStart); out.putBoolean("clickedConsent",clickedConsent);
        finish(clickedConsent ? 0 : 1,out);
    }
    private static void flatten(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> output) {
        output.add(node);
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i); if(child!=null) flatten(child,output);
        }
    }
    private static boolean click(AccessibilityNodeInfo node) {
        for(int i=0;i<4 && node!=null;i++,node=node.getParent())
            if(node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }
}
