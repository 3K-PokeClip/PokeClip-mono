package com.pokeclip.clip.broadcast;

public enum LifecycleEventType {
    BROADCAST_STARTED("broadcast.started"),
    BROADCAST_ENDED("broadcast.ended");

    private final String wireValue;

    LifecycleEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 모르는 종류는 거부한다. 조용히 무시하면 1번이 새 이벤트를 냈을 때 우리가
     * 안 받고 있다는 사실이 아무 데도 안 남는다.
     */
    public static LifecycleEventType from(String wireValue) {
        for (LifecycleEventType type : values()) {
            if (type.wireValue.equals(wireValue)) {
                return type;
            }
        }
        throw new IllegalArgumentException("모르는 생명주기 이벤트: " + wireValue);
    }

    public String wireValue() {
        return wireValue;
    }
}
