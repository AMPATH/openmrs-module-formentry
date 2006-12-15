package org.openmrs.module.formEntry.web.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.formEntry.FormEntryQueue;
import org.openmrs.module.formEntry.FormEntryService;
import org.springframework.web.servlet.mvc.SimpleFormController;

public class FormEntryQueueListController extends SimpleFormController {
	
    /** Logger for this class and subclasses */
    protected final Log log = LogFactory.getLog(getClass());

    protected Object formBackingObject(HttpServletRequest request) throws ServletException {
		
		//default empty Object
		List<FormEntryQueue> queueList = new Vector<FormEntryQueue>();
		
		//only fill the Object is the user has authenticated properly
		if (Context.isAuthenticated()) {
			FormEntryService fs = (FormEntryService)Context.getService(FormEntryService.class);
	    	return fs.getFormEntryQueues();
		}
    	
        return queueList;
    }

	protected Map referenceData(HttpServletRequest request) throws Exception {
		//default empty Objects
		Integer queueSize = 0;
		Integer archiveSize = 0;
		Integer errorSize = 0;
		
		//only fill the Objects if the user has authenticated properly
		if (Context.isAuthenticated()) {
			FormEntryService fs = (FormEntryService)Context.getService(FormEntryService.class);
			queueSize = fs.getFormEntryQueueSize();
	    	archiveSize = fs.getFormEntryArchiveSize();
	    	errorSize = fs.getFormEntryErrorSize();
		}
    	
		Map<String, Object> map = new HashMap<String, Object>();
		
		map.put("queueSize", queueSize);
		map.put("archiveSize", archiveSize);
		map.put("errorSize", errorSize);
		
        return map;
	}
	
}