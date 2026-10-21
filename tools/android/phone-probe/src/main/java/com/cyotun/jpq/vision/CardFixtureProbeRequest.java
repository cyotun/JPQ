package com.cyotun.jpq.vision;

/** Validates the deliberately narrow launch contract for the card fixture display. */
final class CardFixtureProbeRequest {
    static final int DEFAULT_TIMEOUT_SECONDS=180;
    static final int MIN_TIMEOUT_SECONDS=15;
    static final int MAX_TIMEOUT_SECONDS=300;

    final int caseIndex;
    final boolean portrait;
    final int timeoutSeconds;

    private CardFixtureProbeRequest(int caseIndex,boolean portrait,int timeoutSeconds) {
        this.caseIndex=caseIndex;
        this.portrait=portrait;
        this.timeoutSeconds=timeoutSeconds;
    }

    static CardFixtureProbeRequest parse(boolean optedIn,Integer caseIndex,String orientation,Integer timeoutSeconds) {
        if(!optedIn || caseIndex==null || caseIndex<1 || caseIndex>4) return null;
        final boolean portrait;
        if("portrait".equals(orientation)) portrait=true;
        else if("landscape".equals(orientation)) portrait=false;
        else return null;
        int timeout=timeoutSeconds==null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        if(timeout<MIN_TIMEOUT_SECONDS || timeout>MAX_TIMEOUT_SECONDS) return null;
        return new CardFixtureProbeRequest(caseIndex,portrait,timeout);
    }
}
