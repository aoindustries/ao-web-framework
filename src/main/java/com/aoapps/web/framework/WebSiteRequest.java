/*
 * ao-web-framework - Legacy servlet-based web framework, superfast and capable but tedious to use.
 * Copyright (C) 2000-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-web-framework.
 *
 * ao-web-framework is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-web-framework is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-web-framework.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.web.framework;

import com.aoapps.html.servlet.FlowContent;
import com.aoapps.lang.Strings;
import com.aoapps.lang.io.ContentType;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.net.URIDecoder;
import com.aoapps.net.URIEncoder;
import com.aoapps.net.URIParameters;
import com.aoapps.net.URIParametersMap;
import com.aoapps.net.URIParser;
import com.aoapps.security.Identifier;
import com.aoapps.servlet.attribute.ScopeEE;
import com.aoapps.servlet.http.HttpServletUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

/**
 * A <code>WebSiteSettings</code> contains all the values that a user may customize while they view the web site.
 *
 * @author  AO Industries, Inc.
 */
public class WebSiteRequest extends HttpServletRequestWrapper {

  /**
   * Parameter the contains the search query for in-site search on the current page.
   */
  public static final String SEARCH_QUERY = "search_query";

  /**
   * Parameter that contains the search target (current {@link #SEARCH_ENTIRE_SITE} or {@link #SEARCH_THIS_AREA}).
   */
  public static final String SEARCH_TARGET = "search_target";

  /**
   * Parameter value for {@link #SEARCH_TARGET} to search the entire site.
   */
  public static final String SEARCH_ENTIRE_SITE = "entire_site";

  /**
   * Parameter value for {@link #SEARCH_TARGET} to search the current area of the site.
   */
  public static final String SEARCH_THIS_AREA = "this_area";

  /**
   * Parameter that selects the {@link WebPageLayout}.
   */
  // Matches aoweb-struts/Constants.LAYOUT
  public static final ScopeEE.Session.Attribute<String> LAYOUT =
      ScopeEE.SESSION.attribute("layout");

  /**
   * Parameter name used for logout requests.
   * Will perform logout when has a value that parses to {@link Boolean#TRUE}.
   *
   * @see  Boolean#parseBoolean(java.lang.String)
   */
  public static final String LOGOUT_REQUESTED = "logout_requested";

  /**
   * Parameter name used for login requests.
   * Will perform login when has a value that parses to {@link Boolean#TRUE}.
   *
   * @see  Boolean#parseBoolean(java.lang.String)
   */
  public static final String LOGIN_REQUESTED = "login_requested";

  /**
   * Parameter that contains the login username during authentication.
   */
  public static final String LOGIN_USERNAME = "login_username";

  /**
   * Parameter that contains the login password during authentication.
   */
  public static final String LOGIN_PASSWORD = "login_password";

  private static final Logger logger = Logger.getLogger(WebSiteRequest.class.getName());

  /**
   * Gets the upload directory.
   */
  // TODO: It would be good form for each user to have their own upload directory by username
  private static File getFileUploadDirectory(ServletContext servletContext) throws FileNotFoundException {
    File uploadDir = new File(
        ScopeEE.Application.TEMPDIR.context(servletContext).get(),
        "uploads"
    );
    if (
        !uploadDir.exists()
            && !uploadDir.mkdirs()
            // Check exists again, another thread may have created it and interfered with mkdirs
            && !uploadDir.exists()
    ) {
      throw new FileNotFoundException(uploadDir.getPath());
    }
    return uploadDir;
  }

  private static final SecureRandom secureRandom = new SecureRandom();

  /**
   * Gets the random number generator used for this request.
   *
   * <p>Note: This is not a {@linkplain SecureRandom#getInstanceStrong() strong instance} to avoid blocking.</p>
   */
  public SecureRandom getSecureRandom() {
    return secureRandom;
  }

  private static final Random fastRandom = new Random(IoUtils.bufferToLong(secureRandom.generateSeed(Long.BYTES)));

  /**
   * A fast pseudo-random number generator for non-cryptographic purposes.
   */
  public Random getFastRandom() {
    return fastRandom;
  }

  private static String getExtension(String filename) {
    int pos = filename.lastIndexOf('.');
    if (pos == -1 || pos == (filename.length() - 1)) {
      return filename;
    } else {
      return filename.substring(pos + 1);
    }
  }

  private static class MimeTypeLock {
    // Empty lock class to help heap profile
  }

  private static final MimeTypeLock mimeTypeLock = new MimeTypeLock();
  private static Map<String, String> mimeTypes;

  // TODO: Should client-provided content-type take priority?
  private static String getContentType(Part part, String filename) throws IOException {
    synchronized (mimeTypeLock) {
      if (mimeTypes == null) {
        Map<String, String> newMap = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(WebSiteRequest.class.getResourceAsStream("mime.types")))) {
          String line;
          while ((line = in.readLine()) != null) {
            if (line.length() > 0) {
              if (line.charAt(0) != '#') {
                String[] words = Strings.split(line);
                if (words.length > 0) {
                  String type = words[0];
                  for (int c = 1; c < words.length; c++) {
                    newMap.put(words[1], type);
                  }
                }
              }
            }
          }
        }
        mimeTypes = newMap;
      }
      String type = mimeTypes.get(getExtension(filename).toLowerCase());
      if (type != null) {
        return type;
      }
      return part.getContentType();
    }
  }

  // TODO: One ConcurrentMap per ServletContext
  private static final Map<Identifier, UploadedFile> uploadedFiles = new HashMap<>();

  private Identifier getNextId() {
    synchronized (uploadedFiles) {
      while (true) {
        Identifier id = new Identifier(getSecureRandom());
        if (!uploadedFiles.containsKey(id)) {
          uploadedFiles.put(id, null);
          return id;
        }
      }
    }
  }

  // TODO: Consider using ao-concurrent to avoid keeping a thread sleeping.
  private static volatile Thread uploadedFileCleanup;

  private static void addUploadedFile(UploadedFile uf, final ServletContext servletContext) {
    synchronized (uploadedFiles) {
      uploadedFiles.put(uf.getId(), uf);

      if (uploadedFileCleanup == null) {
        uploadedFileCleanup = new Thread() {
          @Override
          @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "SleepWhileInLoop"})
          public void run() {
            final Thread currentThread = Thread.currentThread();
            while (currentThread == uploadedFileCleanup && !currentThread.isInterrupted()) {
              try {
                sleep(10L * 60 * 1000);

                // Remove the expired entries
                synchronized (uploadedFiles) {
                  Iterator<Identifier> iter = uploadedFiles.keySet().iterator();
                  while (iter.hasNext()) {
                    Identifier id = iter.next();
                    UploadedFile uf = uploadedFiles.get(id);
                    if (uf == null) {
                      iter.remove();
                    } else {
                      long timeSince = System.currentTimeMillis() - uf.getLastAccessed();
                      if (timeSince < 0 || timeSince >= (60L * 60 * 1000)) {
                        File file = uf.getStorageFile();
                        if (file.exists()) {
                          try {
                            Files.delete(file.toPath());
                          } catch (IOException e) {
                            logger.log(
                                Level.SEVERE,
                                "file.getPath()=" + file.getPath(),
                                e
                            );
                          }
                        }
                        iter.remove();
                      }
                    }
                  }
                  // Delete the files that do not have an uploadedFile entry and are at least two hours old
                  File dir = getFileUploadDirectory(servletContext);
                  String[] list = dir.list();
                  if (list != null) {
                    for (String filename : list) {
                      File file = new File(dir, filename);
                      long fileAge = System.currentTimeMillis() - file.lastModified();
                      if (
                          fileAge < (-2L * 60 * 60 * 1000)
                              || fileAge > (2L * 60 * 60 * 1000)
                      ) {
                        boolean found = false;
                        iter = uploadedFiles.keySet().iterator();
                        while (iter.hasNext()) {
                          UploadedFile uf = uploadedFiles.get(iter.next());
                          if (uf.getStorageFile().getAbsolutePath().equals(file.getAbsolutePath())) {
                            found = true;
                            break;
                          }
                        }
                        if (!found) {
                          try {
                            Files.delete(file.toPath());
                          } catch (IOException e) {
                            logger.log(
                                Level.SEVERE,
                                "file.getPath()=" + file.getPath(),
                                e
                            );
                          }
                        }
                      }
                    }
                  }
                }
              } catch (ThreadDeath td) {
                throw td;
              } catch (InterruptedException err) {
                if (currentThread == uploadedFileCleanup) {
                  logger.log(Level.WARNING, null, err);
                }
                // Restore the interrupted status
                Thread.currentThread().interrupt();
              } catch (Throwable t) {
                logger.log(Level.SEVERE, null, t);
                try {
                  sleep(60L * 1000);
                } catch (InterruptedException err) {
                  logger.log(Level.WARNING, null, err);
                  // Restore the interrupted status
                  Thread.currentThread().interrupt();
                }
              }
            }
          }
        };
        uploadedFileCleanup.start();
      }
    }
  }

  private static void stopUploadedFileCleanup() {
    synchronized (uploadedFiles) {
      Thread t = uploadedFileCleanup;
      if (t != null) {
        uploadedFileCleanup = null;
        t.interrupt();
      }
    }
  }

  /**
   * Shuts-down background clean-up thread on application stop.
   */
  @WebListener("Shuts-down background clean-up thread on application stop.")
  public static class Initializer implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
      // Nothing to do
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
      stopUploadedFileCleanup();
    }
  }

  protected final WebPage sourcePage;
  private final HttpServletRequest req;
  private List<UploadedFile> reqUploadedFiles;

  private boolean isLynx;
  private boolean isLynxDone;

  private boolean isBlackBerry;
  private boolean isBlackBerryDone;

  private boolean isLinux;
  private boolean isLinuxDone;

  /**
   * Creates a new website request wrapper.
   */
  @SuppressWarnings("OverridableMethodCallInConstructor")
  public WebSiteRequest(WebPage sourcePage, HttpServletRequest req) throws ServletException {
    super(req);
    this.sourcePage = sourcePage;
    this.req = req;
    String contentType = req.getHeader("content-type");
    if (
        contentType != null
            && contentType.length() >= ContentType.FORM_DATA.length()
            && contentType.substring(0, ContentType.FORM_DATA.length()).equalsIgnoreCase(ContentType.FORM_DATA)
    ) {
      try {
        boolean keepFiles = false;
        try {
          try {
            // Determine the authentication info
            WebSiteUser user = getWebSiteUser(null);
            if (user != null) {
              File uploadDirectory = getFileUploadDirectory(getServletContext());
              keepFiles = true;
              // Create an UploadedFile for each file in the MultipartRequest
              reqUploadedFiles = new ArrayList<>();
              for (Part part : req.getParts()) {
                // Get a copy of the file in our upload directory
                File file;
                while (true) {
                  File newFile = new File(uploadDirectory, String.valueOf(getNextId()));
                  if (!newFile.exists()) {
                    file = newFile;
                    break;
                  }
                }
                part.write(file.getCanonicalPath());

                String filename = part.getName();
                // Not necessary since there is a clean-up thread: file.deleteOnExit(); // JDK implementation builds an ever-growing set
                UploadedFile uf = new UploadedFile(
                    HttpServletUtil.getSubmittedFileName(part),
                    file,
                    user,
                    getContentType(part, filename) // TODO: Should this be the submitted filename?
                );
                addUploadedFile(uf, sourcePage.getServletContext());
                reqUploadedFiles.add(uf);
              }
            }
          } catch (LoginException err) {
            // Ignore the error, just allow the files to be cleaned up because keepFiles is still false
          }
        } finally {
          if (!keepFiles) {
            for (Part part : req.getParts()) {
              part.delete();
            }
          }
        }
      } catch (IOException e) {
        throw new ServletException(e);
      }
    }
  }

  /**
   * Appends an already-encoded parameter to a URL.
   *
   * @param  encodedName   the encoded name
   * @param  encodedValue  the encoded value
   */
  protected static boolean appendEncodedParam(StringBuilder url, String encodedName, String encodedValue, boolean hasQuery) {
    assert encodedName.equals(URIEncoder.encodeURIComponent(URIDecoder.decodeURIComponent(encodedName)));
    url.append(hasQuery ? '&' : '?').append(encodedName);
    if (encodedValue != null) {
      assert encodedValue.equals(URIEncoder.encodeURIComponent(URIDecoder.decodeURIComponent(encodedValue)));
      url.append('=').append(encodedValue);
    }
    return true;
  }

  /**
   * Appends a parameter to a URL.
   *
   * @param  name   the raw, unencoded name
   * @param  value  the raw, unencoded value
   */
  protected static boolean appendParam(StringBuilder url, String name, String value, boolean hasQuery) {
    url.append(hasQuery ? '&' : '?');
    URIEncoder.encodeURIComponent(name, url);
    url.append('=');
    URIEncoder.encodeURIComponent(value, url);
    return true;
  }

  /**
   * Appends a parameter to a URL.
   *
   * @param  name   the raw, unencoded name
   * @param  value  the raw, unencoded value
   *
   * @param  finishedParams  Only adds a value when the name has not already been added to the URL.
   *                         This does not support multiple values, only the first is used.
   */
  protected static boolean appendParam(StringBuilder url, String name, String value, Set<String> finishedParams, boolean hasQuery) {
    if (finishedParams.add(name)) {
      hasQuery = appendParam(url, name, value, hasQuery);
    }
    return hasQuery;
  }

  /**
   * Appends the parameters to a URL.
   *
   * @param  finishedParams  Only adds a value when the name has not already been added to the URL.
   *                         This does not support multiple values, only the first is used.
   */
  protected static boolean appendParams(StringBuilder url, URIParameters params, Set<String> finishedParams, boolean hasQuery) {
    if (params != null) {
      Iterator<String> names = params.getParameterNames();
      while (names.hasNext()) {
        String name = names.next();
        if (finishedParams.add(name)) {
          hasQuery = appendParam(url, name, params.getParameter(name), hasQuery);
        }
      }
    }
    return hasQuery;
  }

  /**
   * Gets a context-relative URL given its classname and optional parameters/fragment.
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getUrlForClass(String classname, URIParameters params, String fragment) throws ServletException {
    try {
      Class<? extends WebPage> clazz = Class.forName(classname).asSubclass(WebPage.class);
      String url = getUrl(clazz, params);
      if (fragment != null) {
        url += fragment;
      }
      return url;
    } catch (ClassNotFoundException err) {
      throw new ServletException("Unable to load class: " + classname, err);
    }
  }

  /**
   * {@linkplain #getUrlForClass(java.lang.String, com.aoapps.net.URIParameters, java.lang.String) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrlForClass(String classname, URIParameters params, String fragment, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForClass(classname, params, fragment)
        )
    );
  }

  /**
   * Gets a context-relative URL given its classname and optional parameters.
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getUrlForClass(String classname, URIParameters params) throws ServletException {
    return getUrlForClass(classname, params, null);
  }

  /**
   * {@linkplain #getUrlForClass(java.lang.String, com.aoapps.net.URIParameters) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrlForClass(String classname, URIParameters params, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForClass(classname, params)
        )
    );
  }

  /**
   * Gets a context-relative URL from a String containing a classname and optional parameters/fragment.
   * Parameters and fragment should already be URL encoded but not XML encoded.
   */
  public String getUrlForClass(String classAndParamsFragment) throws ServletException {
    String className;
    String params;
    String fragment;
    int pos = URIParser.getPathEnd(classAndParamsFragment);
    if (pos >= classAndParamsFragment.length()) {
      className = classAndParamsFragment;
      params = null;
      fragment = null;
    } else {
      className = classAndParamsFragment.substring(0, pos);
      if (classAndParamsFragment.charAt(pos) == '?') {
        int hashPos = classAndParamsFragment.indexOf('#', pos + 1);
        if (hashPos == -1) {
          params = classAndParamsFragment.substring(pos + 1);
          fragment = null;
        } else {
          params = classAndParamsFragment.substring(pos + 1, hashPos);
          fragment = classAndParamsFragment.substring(hashPos + 1);
        }
      } else {
        assert classAndParamsFragment.charAt(pos) == '#';
        params = null;
        fragment = classAndParamsFragment.substring(pos + 1);
      }
    }
    return getUrlForClass(
        className,
        (params == null || params.isEmpty()) ? null : new URIParametersMap(params),
        fragment
    );
  }

  /**
   * {@linkplain #getUrlForClass(java.lang.String) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   */
  public String getEncodedUrlForClass(String classAndParamsFragment, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForClass(classAndParamsFragment)
        )
    );
  }

  /**
   * Gets the context-relative URL, optionally with the settings embedded.
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   *
   * @param  path  the context-relative path, with a beginning slash
   */
  public String getUrlForPath(String path, URIParameters params, boolean keepSettings) throws ServletException {
    StringBuilder url = new StringBuilder();
    url.append(path);
    Set<String> finishedParams = new HashSet<>();
    boolean hasQuery = appendParams(url, params, finishedParams, false);
    if (keepSettings) {
      /*hasQuery = */appendSettings(finishedParams, hasQuery, url);
    }
    return url.toString();
  }

  /**
   * {@linkplain #getUrlForPath(java.lang.String, com.aoapps.net.URIParameters, boolean) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  path  the context-relative path, with a beginning slash
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrlForPath(String path, URIParameters params, boolean keepSettings, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForPath(path, params, keepSettings)
        )
    );
  }

  protected boolean appendSettings(Set<String> finishedParams, boolean hasQuery, StringBuilder url) {
    return hasQuery;
  }

  @Override
  public String getParameter(String name) {
    return req.getParameter(name);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Enumeration<String> getParameterNames() {
    return req.getParameterNames();
  }

  @Override
  public String[] getParameterValues(String name) {
    return req.getParameterValues(name);
  }

  /**
   * Gets the context-relative URL to a web page.
   */
  public String getUrl(WebPage page) throws ServletException {
    return getUrl(page, (URIParameters) null);
  }

  /**
   * {@linkplain #getUrl(com.aoapps.web.framework.WebPage) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   */
  public String getEncodedUrl(WebPage page, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrl(page)
        )
    );
  }

  /**
   * Gets the context-relative URL to a web page.
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getUrl(WebPage page, URIParameters params) throws ServletException {
    Set<String> finishedParams = new HashSet<>();
    StringBuilder url = new StringBuilder();
    url.append(page.getUrlPath());
    boolean hasQuery = appendParams(url, params, finishedParams, false);
    hasQuery = appendParams(url, page.getUrlParams(this), finishedParams, hasQuery);

    /*hasQuery = */appendSettings(finishedParams, hasQuery, url);

    return url.toString();
  }

  /**
   * {@linkplain #getUrl(com.aoapps.web.framework.WebPage, com.aoapps.net.URIParameters) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrl(WebPage page, URIParameters params, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrl(page, params)
        )
    );
  }

  /**
   * Gets the context-relative URL to a web page.
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getUrl(Class<? extends WebPage> clazz, URIParameters params) throws ServletException {
    return getUrl(
        WebPage.getWebPage(sourcePage.getServletContext(), clazz, params),
        params
    );
  }

  /**
   * {@linkplain #getUrl(java.lang.Class, com.aoapps.net.URIParameters) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrl(Class<? extends WebPage> clazz, URIParameters params, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrl(clazz, params)
        )
    );
  }

  public String getUrl(Class<? extends WebPage> clazz) throws ServletException {
    return getUrl(clazz, (URIParameters) null);
  }

  /**
   * {@linkplain #getUrl(java.lang.Class) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   */
  public String getEncodedUrl(Class<? extends WebPage> clazz, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrl(clazz)
        )
    );
  }

  /**
   * Gets the URL String with the given parameters embedded, keeping the current settings.
   *
   * @param  path  the context-relative path, with a beginning slash
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getUrlForPath(String path, URIParameters params) throws ServletException {
    return getUrlForPath(path, params, true);
  }

  /**
   * Gets the URL String, keeping the current settings.
   *
   * @param  path  the context-relative path, with a beginning slash
   */
  public String getUrlForPath(String path) throws ServletException {
    return getUrlForPath(path, (URIParameters) null);
  }

  /**
   * {@linkplain #getUrlForPath(java.lang.String, com.aoapps.net.URIParameters) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  path  the context-relative path, with a beginning slash
   *
   * @param  params  Only adds a value when the name has not already been added to the URL.
   *                 This does not support multiple values, only the first is used.
   */
  public String getEncodedUrlForPath(String path, URIParameters params, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForPath(path, params)
        )
    );
  }

  /**
   * {@linkplain #getUrlForPath(java.lang.String) Gets the URL}.  Including:
   * <ol>
   * <li>Prefixing {@linkplain HttpServletRequest#getContextPath() context path}</li>
   * <li>Encoded to ASCII-only <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a> format</li>
   * <li>Then {@linkplain HttpServletResponse#encodeURL(java.lang.String) response encoding}</li>
   * </ol>
   *
   * @param  path  the context-relative path, with a beginning slash
   */
  public String getEncodedUrlForPath(String path, HttpServletResponse resp) throws ServletException {
    return resp.encodeURL(
        URIEncoder.encodeURI(
            getContextPath() + getUrlForPath(path)
        )
    );
  }

  /**
   * Determines if the request is for a Lynx browser.
   */
  public boolean isLynx() {
    if (!isLynxDone) {
      String agent = req.getHeader("user-agent");
      isLynx = agent != null && agent.toLowerCase(Locale.ROOT).contains("lynx");
      isLynxDone = true;
    }
    return isLynx;
  }

  /**
   * Determines if the request is for a BlackBerry browser.
   */
  public boolean isBlackBerry() {
    if (!isBlackBerryDone) {
      String agent = req.getHeader("user-agent");
      isBlackBerry = (agent != null) && agent.startsWith("BlackBerry");
      isBlackBerryDone = true;
    }
    return isBlackBerry;
  }

  /**
   * Determines if the request is for a Linux browser.
   */
  public boolean isLinux() {
    if (!isLinuxDone) {
      String agent = req.getHeader("user-agent");
      isLinux = (agent == null) || agent.toLowerCase(Locale.ROOT).contains("linux");
      isLinuxDone = true;
    }
    return isLinux;
  }

  /**
   * Prints the hidden variables that contain all of the current settings.
   */
  public <__ extends FlowContent<__>> void printFormFields(__ form) throws ServletException, IOException {
    // Do nothing
  }

  // Unused 2021-02-22:
  ///**
  // * Prints the hidden variables that contain all of the current settings.
  // */
  //protected static <__ extends FlowContent<__>> void printHiddenField(__ form form, String name, String value) throws IOException {
  //  form.input().hidden().name(name).value(value).__().autoNl();
  //}

  /**
   * Gets the set of files uploaded during this request.
   */
  public List<UploadedFile> getUploadedFiles() {
    if (reqUploadedFiles == null) {
      Collections.emptyList();
    }
    return Collections.unmodifiableList(reqUploadedFiles);
  }

  /**
   * Gets a file that was uploaded given its ID.  The authentication
   * credentials for this request must match those of the provided ID.
   *
   * @param  owner  the owner of the object
   *
   * @return  the {@link UploadedFile} or <code>null</code> if not found
   *
   * @exception  SecurityException  if the ID is not assigned to the person logged in
   */
  public static UploadedFile getUploadedFile(WebSiteUser owner, Identifier id, ServletContext context) throws SecurityException {
    synchronized (uploadedFiles) {
      UploadedFile uf = uploadedFiles.get(id);
      if (uf != null) {
        if (uf.getOwner().equals(owner)) {
          return uf;
        } else {
          logger.log(
              Level.SEVERE,
              "UploadedFile found, but owner doesn''t match: uf.getOwner()=\"{0}\", owner=\"{1}\".",
              new Object[]{
                  uf.getOwner(),
                  owner
              }
          );
        }
      }
      return null;
    }
  }

  /**
   * Gets the person who is logged in or <code>null</code> if no login is performed for this request.
   *
   * @param  resp  The current response or {@code null} when invoked from {@link WebPage#getLastModified(javax.servlet.http.HttpServletRequest)}
   *
   * @exception LoginException if an invalid login attempt is made or the user credentials are not found
   */
  public WebSiteUser getWebSiteUser(HttpServletResponse resp) throws ServletException, LoginException {
    return null;
  }

  /**
   * Determines if the user is currently logged in.
   */
  public boolean isLoggedIn() throws ServletException {
    try {
      return getWebSiteUser(null) != null;
    } catch (LoginException err) {
      return false;
    }
  }

  /**
   * Logs out the current user or does nothing if not logged in.
   */
  public void logout(HttpServletResponse resp) {
    // Do nothing
  }
}
