// [TESTS](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
// Renders one citation using the BUILT artefact's classpath, offline.
//
// This is a packaging check, not a unit test, and it has to be run this way to be
// worth anything: citation rendering goes through GraalJS, whose language
// registrations live in resources that two dependency jars publish at the same
// path, and the Maven test classpath keeps those jars separate whatever the
// packaging does to them. A test run by surefire therefore passes while the thing
// a user runs fails, which is exactly what happened in issue-022.
//
// Run with the single-file source launcher, so there is nothing to compile:
//   java -cp target/<the jar> scripts/RenderCheck.java
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.terminological.bibliography.api.crossref.CrossRefWork;
import uk.co.terminological.bibliography.record.MergedRecord;

public class RenderCheck {

	public static void main(String[] args) throws Exception {
		var work = new CrossRefWork(
				new ObjectMapper().readTree(
					"{\"DOI\":\"10.1136/bmjqs-2018-008370\",\"type\":\"journal-article\","
							+ "\"publisher\":\"BMJ\",\"title\":[\"Artificial intelligence, bias and clinical safety\"],"
							+ "\"container-title\":[\"BMJ Quality & Safety\"],"
							+ "\"author\":[{\"given\":\"Robert\",\"family\":\"Challen\"}],"
							+ "\"issued\":{\"date-parts\":[[2019,1,12]]}}"
				)
		);

		// both, because the tools return merged records and the renderer is reached
		// through Record.render either way
		check("record", work.render("ieee"));
		check("merged record", MergedRecord.from(work)
			.render("ieee"));
		System.out.println("Citation rendering OK from the built artefact");
	}

	private static void check(String what, java.util.Optional<String> rendered) {
		// render() answers an unrenderable record with an empty Optional and says
		// nothing, which is how this failure stayed invisible
		if (rendered.isEmpty() || !rendered.get()
			.contains("Challen")) {
			System.err.println(
				"FAILED: no citation rendered for a " + what
						+ " — got " + rendered
						+ ". See issue-022: a packaging that merges dependency"
						+ " resources breaks the citeproc javascript engine."
			);
			System.exit(1);
		}
	}

}
