package com.aoindustries.website.framework;

/*
 * Copyright 2004-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;

/**
 * @see  WebSiteRequest#getOutputCacheKey()
 *
 * @author  AO Industries, Inc.
 */
public class WebSiteRequestCacheKey {

    public final boolean isSearchEngine;
    public final String layout;

    public WebSiteRequestCacheKey(WebSiteRequest req) {
        Profiler.startProfile(Profiler.FAST, WebSiteRequestCacheKey.class, "<init>(WebSiteRequest)", null);
        try {
            this.isSearchEngine=req.isSearchEngine();
            String layout=(String)req.getSession().getAttribute("layout");
            if(layout==null) layout=req.isLynx() || req.isBlackBerry() ? "Text" : "Default";
            this.layout=layout;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    public int hashCode() {
        Profiler.startProfile(Profiler.FAST, WebSiteRequestCacheKey.class, "hashCode()", null);
        try {
            if(isSearchEngine) return -layout.hashCode();
            return layout.hashCode();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    public boolean equals(Object O) {
        Profiler.startProfile(Profiler.FAST, WebSiteRequestCacheKey.class, "equals(Object)", null);
        try {
            if(O==null) return false;
            if(!(O instanceof WebSiteRequestCacheKey)) return false;
            WebSiteRequestCacheKey otherKey=(WebSiteRequestCacheKey)O;
            return
                isSearchEngine==otherKey.isSearchEngine
                && layout.equals(otherKey.layout)
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}
