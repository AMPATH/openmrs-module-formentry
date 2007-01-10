package org.openmrs.module.formentry.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.formentry.FormEntryUtil;
import org.openmrs.util.OpenmrsUtil;

/**
 * Provides a servlet through which an XSN is downloaded. This class differs
 * from org.openmrs.module.formentry.FormDownloadServlet in that this class /will not/
 * modify the template or schema files inside of the xsn. This class simply
 * writes the named schema to the response
 * 
 * @author Ben Wolfe
 * @version 1.0
 */
public class XsnDownloadServlet extends HttpServlet {

	public static final long serialVersionUID = 123424L;

	private Log log = LogFactory.getLog(this.getClass());

	protected void doGet(HttpServletRequest request, HttpServletResponse response)
	    throws ServletException, IOException {

		response.setHeader("Content-Type", "text/plain; charset=utf-8");

		// since we've got a "/formentry/form/*" servlet-mapping,
		// getServletPath() will only return /formentry/form.
		String filename = request.getRequestURI();
		// get only the file name out of path
		filename = filename.substring(filename.lastIndexOf("/") + 1);
		
		File file = FormEntryUtil.getXSNFile(filename);
		
		try {
			Long modified = file.lastModified();
			if (modified == 0)
				log.error("Last Modified date was zero for: " + file.getAbsolutePath());
			
			log.debug("testing modified date: " + new Date(modified));
			log.debug("testing etag: " + modified);
			
			// InfoPath checks one or both of these values to determine if it needs to 
			// update its internal/local cache
			response.setDateHeader("Last-Modified", modified);
			response.setHeader("ETag", "" + modified);
			
			FileInputStream formStream = new FileInputStream(file);
			OpenmrsUtil.copyFile(formStream, response.getOutputStream());
		} 
		catch (FileNotFoundException e) {
			log
			    .error(
			        "The request for '"
			        	+ file.getAbsolutePath()
			            + "' cannot be found.  More than likely the XSN has not been uploaded (via Upload XSN in Form Entry administration).",
			        e);
			response.sendError(404);
		}
	}

	

}
