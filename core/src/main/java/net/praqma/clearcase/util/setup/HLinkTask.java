package net.praqma.clearcase.util.setup;

import net.praqma.clearcase.exceptions.ClearCaseException;

import org.w3c.dom.Element;

public class HLinkTask extends AbstractTask {

	@Override
	public void parse( Element e ) throws ClearCaseException {
		String name = e.getAttribute( "name" );
		String from = e.getAttribute( "name" );
		
	}

}