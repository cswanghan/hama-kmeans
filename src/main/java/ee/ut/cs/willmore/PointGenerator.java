package ee.ut.cs.willmore;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public interface PointGenerator {

	/**
	 * Create dataset of points saved on the Hadoop
	 * file-system at the specified {@code fileName}. 
	 * {@code numPoints} of 3D double-value points, with dimensions X,Y and Z each within [0,{@code range}).
	 * 
	 * @param fileSys
	 * @param fileName
	 * @param numPoints
	 * @param range
	 * @throws IOException
	 */
	void generateSourceFile(FileSystem fileSys, Path fileName, 
			int numPoints, int range) throws IOException;
	
}
