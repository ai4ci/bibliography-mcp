package uk.co.terminological.bibliography.scholar;

import uk.co.terminological.fluentxml.XmlDocElement;
import uk.co.terminological.fluentxml.XmlElement;
import uk.co.terminological.fluentxml.XmlException;

@Deprecated
public class GoogleResult {
	
	private XmlDocElement raw;
	public GoogleResult(XmlDocElement raw) throws XmlException {
		this.raw=raw; 
		convert();
	}
	
	private void convert() {
		// TODO Auto-generated method stub
		// /html/body/div/div[11]/div[2]/div[2]/div[2]/div/div[2]
		// document.querySelector("#gs_res_ccl_mid > div > div.gs_ri")
		// #gs_res_ccl_mid > div > div.gs_ri
		try {
			raw.doXpath(".//*[@id='gs_res_ccl_mid']/div/div[contains(concat(' ', normalize-space(@class), ' '), ' gs_ri ')]").getManyAsStream(XmlElement.class);
			//TODO: Create result reference.
		} catch (XmlException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public XmlElement raw() {return raw;}
}
