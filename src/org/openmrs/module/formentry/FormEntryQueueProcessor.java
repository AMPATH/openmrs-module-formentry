package org.openmrs.module.formentry;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Form;
import org.openmrs.api.APIException;
import org.openmrs.api.FormService;
import org.openmrs.api.context.Context;
import org.openmrs.hl7.HL7InQueue;
import org.openmrs.hl7.HL7Source;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Processes FormEntryQueue entries. Each entry is translated into an HL7
 * message, using the transform associated with the form used to make the entry.
 * When the transform is successful, the queue entry is converted into the
 * FormEntryArchive; for unsuccessful transforms, the queue entry is converted
 * to a FormEntryError.
 * 
 * @author Burke Mamlin
 * @version 1.0
 */
@Transactional
public class FormEntryQueueProcessor /* implements Runnable */{

	private static final Log log = LogFactory
			.getLog(FormEntryQueueProcessor.class);

	private DocumentBuilderFactory documentBuilderFactory;
	private XPathFactory xPathFactory;
	private TransformerFactory transformerFactory;
	private static Boolean isRunning = false; // allow only one running

	// processor per JVM

	/**
	 * Empty constructor (requires context to be set before any other calls are
	 * made)
	 */
	public FormEntryQueueProcessor() {
	}

	/**
	 * Transform a FormEntryQueue entry (converts the XML data into HL7 and
	 * places it into the HL7 inbound queue for further processing). Once
	 * transformed, then FormEntryQueue entry is flagged as completed (the
	 * status is updated).
	 * 
	 * The XSLT from the appropriate form (the form used to generate the
	 * FormEntryQueue data in the first place) is used to perform the
	 * transformation into HL7.
	 * 
	 * @param formEntryQueue
	 *            entry to be transformed
	 */
	public void transformFormEntryQueue(FormEntryQueue formEntryQueue) {
		log.debug("Transforming form entry queue");
		String formData = formEntryQueue.getFormData();
		FormService formService = Context.getFormService();
		Integer formId = null;
        HL7Source hl7Source = null;
		String hl7SourceKey = null;
		String errorDetails = null;

		// First we parse the FormEntry xml data to obtain the formId of the
		// form that was used to create the xml data
		try {
			DocumentBuilderFactory dbf = getDocumentBuilderFactory();
			DocumentBuilder db = dbf.newDocumentBuilder();
			XPathFactory xpf = getXPathFactory();
			XPath xp = xpf.newXPath();
			Document doc = db.parse(new InputSource(new StringReader(formData)));
			formId = Integer.parseInt(xp.evaluate("/form/@id", doc));
			hl7SourceKey = xp.evaluate("/form/header/uid", doc);
		} catch (Exception e) {
			errorDetails = e.getMessage();
			log.error("Error while parsing formentry ("+ formEntryQueue.getFormEntryQueueId() + ")", e);
			setFatalError(formEntryQueue, "Error while parsing the formentry xml", errorDetails);
		}

		// If we failed to obtain the formId, move the queue entry into the
		// error bin and abort
		if (formId == null) {
			setFatalError(formEntryQueue, "Error retrieving form ID from data", errorDetails);
			return;
		}
		
		// If we can't get a form object for this formId, throw this to the error bin
		Form form = formService.getForm(formId);
		if (form == null) {
			setFatalError(formEntryQueue, "The form id: " + formId + " does not exist in the form table!", errorDetails);
			return;
		}

        // Get the HL7 source based on the form's encounter type
        hl7Source = Context.getHL7Service().getHL7SourceByName(
                Context.getAdministrationService().getGlobalProperty(
                    FormEntryConstants.FORMENTRY_GP_DEFAULT_HL7_SOURCE,
                    FormEntryConstants.FORMENTRY_DEFAULT_HL7_SOURCE_NAME));

		// If source key not provided, use FormEntryQueue.formEntryQueueId
		if (hl7SourceKey == null || hl7SourceKey.length() < 1)
			hl7SourceKey = String.valueOf(formEntryQueue.getFormEntryQueueId());

		// Now that we've determined the form used to create the XML data,
		// we can obtain the associated XSLT to perform the transform to HL7.
		String xsltDoc = form.getXslt();

		StringWriter outWriter = new StringWriter();
		Source source = new StreamSource(new StringReader(formData), "UTF-8");
		Source xslt = new StreamSource(IOUtils.toInputStream(xsltDoc));
		Result result = new StreamResult(outWriter);

		TransformerFactory tf = getTransformerFactory();
		String out = null;
		errorDetails = null;
		try {
			Transformer t = tf.newTransformer(xslt);
			t.transform(source, result);
			out = outWriter.toString();
		} catch (TransformerConfigurationException e) {
			errorDetails = e.getMessage();
			log.error(errorDetails, e);
		} catch (TransformerException e) {
			errorDetails = e.getMessage();
			log.error(errorDetails, e);
		}

		// If the transform failed, move the queue entry into the error bin
		// and exit
		if (out == null) {
			setFatalError(formEntryQueue, "Unable to transform to HL7", errorDetails);
			return;
		}

		// At this point, we have successfully transformed the XML data into
		// HL7. Create a new entry in the HL7 inbound queue and move the
		// current FormEntry queue item into the archive.
		HL7InQueue hl7InQueue = new HL7InQueue();
		hl7InQueue.setHL7Data(out.toString());
		hl7InQueue.setHL7Source(hl7Source);
		hl7InQueue.setHL7SourceKey(hl7SourceKey);
		Context.getHL7Service().saveHL7InQueue(hl7InQueue);

		FormEntryArchive formEntryArchive = new FormEntryArchive(formEntryQueue);
		FormEntryService formEntryService = (FormEntryService)Context.getService(FormEntryService.class);
		formEntryService.createFormEntryArchive(formEntryArchive);
		formEntryService.deleteFormEntryQueue(formEntryQueue);

		// clean up memory
		formEntryService.garbageCollect();
	}

	/**
	 * Transform the next pending FormEntryQueue entry. If there are no pending
	 * items in the queue, this method simply returns quietly.
	 * 
	 * @return true if a queue entry was processed, false if queue was empty
	 */
	public boolean transformNextFormEntryQueue() {
		boolean transformOccurred = false;
		FormEntryService fes = null; 
		try {
			fes = (FormEntryService)Context.getService(FormEntryService.class);
		}
		catch (APIException e) {
			log.debug("FormEntryService not found");
			return false;
		}
		FormEntryQueue feq;
		if ((feq = fes.getNextFormEntryQueue()) != null) {
			transformFormEntryQueue(feq);
			transformOccurred = true;
		}
		return transformOccurred;
	}

	/**
	 * @return DocumentBuilderFactory to be used for parsing XML
	 */
	private DocumentBuilderFactory getDocumentBuilderFactory() {
		if (documentBuilderFactory == null)
			documentBuilderFactory = DocumentBuilderFactory.newInstance();
		return documentBuilderFactory;
	}

	/**
	 * @return XPathFactory to be used for obtaining data from the parsed XML
	 */
	private XPathFactory getXPathFactory() {
		if (xPathFactory == null)
			xPathFactory = XPathFactory.newInstance();
		return xPathFactory;
	}

	/**
	 * @return TransformerFactory used to perform the transform to HL7
	 */
	private TransformerFactory getTransformerFactory() {
		if (transformerFactory == null) {
			System.setProperty("javax.xml.transform.TransformerFactory",
				"net.sf.saxon.TransformerFactoryImpl");
			transformerFactory = TransformerFactory.newInstance();
		}
		return transformerFactory;
	}

	/**
	 * Convenience method to handle fatal errors. In this case, a FormEntryError
	 * object is built and stored based on the current queue entry and then the
	 * current queue entry is removed from the queue.
	 * 
	 * @param formEntryQueue
	 *            queue entry with fatal error
	 * @param error
	 *            name and/or brief description of the error
	 * @param errorDetails
	 *            specifics for the fatal error
	 */
	private void setFatalError(FormEntryQueue formEntryQueue, String error,
			String errorDetails) {
		FormEntryError formEntryError = new FormEntryError();
		formEntryError.setFormData(formEntryQueue.getFormData());
		formEntryError.setError(error);
		formEntryError.setErrorDetails(errorDetails);
		FormEntryService formEntryService = (FormEntryService)Context.getService(FormEntryService.class);
		formEntryService.createFormEntryError(formEntryError);
		formEntryService.deleteFormEntryQueue(formEntryQueue);
	}

	/**
	 * Starts up a thread to process all existing FormEntryQueue entries
	 */
	public void processFormEntryQueue() throws APIException {
		synchronized (isRunning) {
			if (isRunning) {
				log.warn("FormEntryQueue processor aborting (another processor already running)");
				return;
			}
			isRunning = true;
		}
		try {
			log.debug("Start processing FormEntry queue");
			log.debug("FormEntry processor hash: " + this.hashCode());
			while (transformNextFormEntryQueue()) {
				// loop until queue is empty
			}
			log.debug("Done processing FormEntry queue");
		}
		finally {
			isRunning = false;
		}
	}

	/*
	 * Run method for processing all entries in the FormEntry queue public void
	 * run() { try { while (transformNextFormEntryQueue()) { // loop until queue
	 * is empty } } catch (Exception e) { log.error("Error while processing
	 * FormEntryQueue", e); } }
	 */

	/*
	 * private static Hashtable<Context, Thread> threadCache = new Hashtable<Context,
	 * Thread>();
	 * 
	 * private static Thread getThreadForContext(Context context) { Thread
	 * thread; if (threadCache.containsKey(context)) thread =
	 * threadCache.get(context); else { thread = new Thread(new
	 * FormEntryQueueProcessor(context)); threadCache.put(context, thread); }
	 * return thread; }
	 */

}
