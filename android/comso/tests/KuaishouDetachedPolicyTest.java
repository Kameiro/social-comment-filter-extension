package dev.local.commentcollector;

import org.json.JSONObject;

final class KuaishouDetachedPolicyTest {
    public static void main(String[] args) throws Exception {
        JSONObject task = new JSONObject().put("kind", "comment").put("auto", true).put("status", "running")
            .put("platformBlocked", false).put("photoId", "ks-1").put("generation", 4)
            .put("params", new JSONObject().put("pagingMode", "auto"));
        assert KuaishouDetachedPolicy.canDetach(task, "ks-1");
        assert !KuaishouDetachedPolicy.canDetach(task, "ks-2");
        assert KuaishouDetachedPolicy.acceptsPage(task, 4, "ks-1");
        assert !KuaishouDetachedPolicy.acceptsPage(task, 3, "ks-1");
        assert !KuaishouDetachedPolicy.acceptsPage(task, 4, "ks-2");
        assert KuaishouDetachedPolicy.shouldRebind(3);
        assert !KuaishouDetachedPolicy.shouldRebind(6);
        System.out.println("Kuaishou detached policy passed");
    }
}
