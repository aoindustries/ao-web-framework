package com.aoindustries.website.framework;

/*
 * Copyright 2005-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.*;
import java.io.*;
import java.sql.*;
import javax.servlet.http.*;

/**
 * Automatically generates the description along with a list of all pages.
 *
 * @author  AO Industries, Inc.
 */
abstract public class DescriptionAutoListPage extends AutoListPage {

    public DescriptionAutoListPage() {
    }

    public DescriptionAutoListPage(WebSiteRequest req) {
	super(req);
    }

    public DescriptionAutoListPage(Object param) {
	super(param);
    }

    /**
     * Prints the content that will be put before the auto-generated list.
     */
    @Override
    public void printContentStart(
	ChainWriter out,
	WebSiteRequest req,
	HttpServletResponse resp
    ) throws IOException, SQLException {
        out.print(getDescription());
    }
}
