import java.lang.reflect.Method;
import com.cycling74.max.DataTypes;
import com.cycling74.msp.MSPObject;
import com.cycling74.msp.MSPPerformable;
import com.cycling74.msp.MSPSignal;

public class autosampler extends MSPObject implements MSPPerformable {
	Method _p = null;

	private double[] 	sample_buffer;
	private double[] 	window_buffer 		= new double[2048];
	private double 		read_write_index 	= 0;
	private double 		window_read_index 	= 0;
	private double 		samplerate 			= 48000; 	// defaults..
	private double 		amplitude 			= 0; 		// amplitude in from max
	private double 		play_rec_prob 		= 50; 		// probability of playing / recording
	private double		input_pitch		 	= 0;
	private double 		delta_playback 		= 1; 		// playback speed ratio / delta index / transposition ratio
	private double 		window_size 		= 1024.00000;
	private double 		delta_window 		= window_size / (delta_playback * 48000); // 1024 samples in the time of recbufsize samples at speed delta_playback
	private int 		recording_time 		= 1000;
	private int 		recbufsize 			= 48000;
	private int 		signalvectorsize 	= 512;
	private int 		maxbuftime 			= 5000; 	// ms
	private int 		maxbufsize 			= 480000; 	// samples
	private boolean 	record 				= true; 	// true = record, false = playback
	private boolean 	gate 				= true; 	// if gate is false allow dsp
	private boolean     pitchgate			= true;
	private String		interp_mode			= "hermite";
	
	/**************************************************************************************/
	// init
	
	private static final String[] INLET_ASSIST = new String[] { "input (sig)" };
	private static final String[] OUTLET_ASSIST = new String[] { "output (sig)" };

	public autosampler() {
		this(5000);
	}

	public autosampler(int lmaxbuftime) {
		declareInlets(new int[] { SIGNAL });
		declareOutlets(new int[] { SIGNAL, DataTypes.ALL });

		setInletAssist(INLET_ASSIST);
		setOutletAssist(OUTLET_ASSIST);

		System.gc();
		
		//post("0612102029___");

		if (lmaxbuftime > 0) {
			maxbuftime = lmaxbuftime;
			init_buffer();
		} else {
			post("autosampler: max buffer time not given, set to 10 seconds");
		}

		_p = getPerformMethod("p");
	}

	/**************************************************************************************/
	// private functions
	
	private void init_buffer() 
	{
		maxbufsize = (int) (samplerate * maxbuftime * 0.001);
		recbufsize = recording_time * (int) (0.001 * samplerate);
		set_delta();

		sample_buffer = new double[(int) (maxbufsize + samplerate)]; 	// add a second to the end for safety

		for (int i = 0; i < window_size; i++){							// init hann window (maybe add some other windowing choices later)
			window_buffer[i] = (0.5 * (1.0 - Math.cos(2 * Math.PI * i	
					/ (window_size - 1))));
		}

		window_buffer[0] = 0; 											// force the first and last elements to be 0, click paranoia is setting in
		window_buffer[(int)window_size] = 0;
		
		clear();														// make sure the sample buffer is clear
	}

	private void checksamplerate(double lchecksamplerate) 
	{
		if (lchecksamplerate != samplerate) {
			samplerate = lchecksamplerate;
			init_buffer();
		}
	}
	
	private void set_delta()
	{
		delta_playback = Math.exp(input_pitch * 0.057762265);			// scale delta values for modified playback speed
		//post("delta_playback " + delta_playback);
		delta_window = (window_size/recbufsize) * delta_playback * 1.1;     // reinit window, sync with delta_playback ******************* THIS IS PROBABLY WHERE THE PROBLEM IS ************************
		//post("delta_window " + delta_window);
	}

	/**************************************************************************************/
	// public functions
	
	public void info()													// just post all functions to the max window
	{
		post("/**************************************************************************************/");
		post("autosampler v071110 © Michael Murphy 2010");
		post(" ");
		post("Function Information: ");
		post("clear() - clear the buffer");
		post("set_amp(double amplitude) - set the amplitude");
		post("set_prob(double play_rec_prob) - probability of the sampler playing or recording");
		post("set_rec_time(int recording_time) - set the time for the sampler to record");
		post("set_pitch(double pitch) - set the pitch of playback in semitones");
		post("set_interpolation_method(String interp_method) - interpolation method (0 - linear, 1 - cubic, 2 - hermite, 3 - lagrange)");
		post("bang() - play or record buffer");
		post(" ");
		post("/**************************************************************************************/");
	}
		
	public void clear() {
		for (int i = 0; i < maxbufsize; i++)
			sample_buffer[i] = 0;
	}

	public void set_amp(double lamplitude) {
		if (lamplitude >= 0 && lamplitude <= 1) { if (record) { amplitude = lamplitude; } } 
		// else { post("autosampler: amplitude should be between 0 and 1"); }
	}

	public void set_prob(double lplay_rec_prob) {
		if (lplay_rec_prob >= 0 && lplay_rec_prob <= 100){ play_rec_prob = lplay_rec_prob; }
		else{ post("autosampler: probability should be between 1 and 100"); }
	}

	public void set_rec_time(int lrecording_time) {
		if (gate) 
		{				
			int maxrectime = (int) (samplerate * maxbufsize);					// don't allow this to change unless the gate is open ie: NO PLAYBACK
			if (lrecording_time > 20 && lrecording_time < maxrectime) 
			{
				recording_time = lrecording_time;
				recbufsize = recording_time * (int)(0.001 * samplerate);
				set_delta();													// set the delta values to the given recbufsize
			} 
			else 
			{
				post("autosampler: recording time was set outside of the bounds 20 - "
						+ maxrectime);
				recording_time = maxbuftime;
			}

		}
	}

	public void set_pitch(double pitch) {
		if (pitch >= -24 && pitch <= 24)
		{
			if(record)
			{			
				input_pitch = pitch;
				//post("pitch_change");
				set_delta();
			}
		}
	}
	
	public void set_interpolation_method(int linterp)
	{
		if(linterp > 0)
		{
			if(linterp == 0){
				interp_mode = "linear";
			}
			else if(linterp == 1){
				interp_mode = "cubic";
			}
			else if(linterp == 2){
				interp_mode = "hermite";
			}
			else if(linterp == 3){
				interp_mode = "lagrange";
			}
			else{
				post("autosampler: interpolation mode not recognised (linear, cubic, hermite, or lagrange supported)");
				interp_mode = "cubic";
			}
		}
	}

	public void bang() 
	{						// call this function on bang -- this function shuts the gate allowing for DSP
		if(gate) 			// if the gate is open, allow for a decision on recording or playback to be made.  Otherwise nothing gets through.
		{

			double lrandom = Math.random();
			double lprob = play_rec_prob * 0.01; // between 1-100 to 0-1

			if (lrandom > lprob) 
			{
				pitchgate = true;
				record = true;					// only recording allowed
				gate = false;					// shut the gate - allow recording or playing from DSP
				outlet(1, "Recording");
			} 
			else 
			{
				record = false;					// no recording allowed
				gate = false;					// shut the gate - allow recording or playing from DSP
				outlet(1, "Playback");
			}

		}
	}

	/**************************************************************************************/
	// interpolation methods

	private double interpolate_linear(double t, double x0, double x1)
	{
		return (x0 * (1 - t) + x1 * t);
	}
	
	private double interpolate_cubic(double t, double x0, double x1, double x2, double x3)
	{
		double a0 = x3 - x2 - x0 + x1;
		double a1 = x0 - x1 - a0;
		double a2 = x2 - x0;
		double a3 = x1;
		return (a0 * (t * t * t)) + (a1 * (t * t)) + (a2 * t) + (a3);
	}

	private double interpolate_hermite_4pt3oX(double t, double x0, double x1, double x2, double x3)
	{
		double c0 = x1;
		double c1 = .5F * (x2 - x0);
		double c2 = x0 - (2.5F * x1) + (2 * x2) - (.5F * x3);
		double c3 = (.5F * (x3 - x0)) + (1.5F * (x1 - x2));
		return (((((c3 * t) + c2) * t) + c1) * t) + c0;
	}

	private double interpolate_lagrange_4po3oZ(double t, double xm1, double x0, double x1, double x2)
	{
		double z = t - 1/2.0; 
		double even1 = xm1+x2, odd1 = xm1-x2; 
		double even2 = x0+x1, odd2 = x0-x1; 
		double c0 = 9/16.0*even2 - 1/16.0*even1; 
		double c1 = 1/24.0*odd1 - 9/8.0*odd2;
		double c2 = 1/4.0*(even1-even2); 
		double c3 = 1/2.0*odd2 - 1/6.0*odd1; 
		return ((c3*z+c2)*z+c1)*z+c0;
	}

	/**************************************************************************************/
	// dsp methods
	
	public Method dsp(MSPSignal[] in, MSPSignal[] out) 
	{
		read_write_index = 0;
		checksamplerate(in[0].sr);
		return _p;
	}
	
	private void p(MSPSignal[] sigin, MSPSignal[] sigout)
	{
		checksamplerate(sigin[0].sr);
		float in[] = sigin[0].vec;
		float out[] = sigout[0].vec;
		int index = 0;
		
		signalvectorsize = in.length;

		if (gate == false) 
		{											// only allow rec/play if gate = FALSE
			gate = false;
			if (record) 
			{
				record = true;															// be positive this is set to true while this completes
				if(read_write_index > signalvectorsize*4) { pitchgate = false; } // allow pitch information to be saved in the first 4 sigvecs of the recorded sample
				
				for (index = 0; index < signalvectorsize; index++) 
				{
					sample_buffer[(int)read_write_index] = in[index];	// write sigvec to buffer
					read_write_index++;
					if (read_write_index >= recbufsize) 
					{
						gate = true; 									// gate TRUE = nothing allowed through
						read_write_index = 0; 							// reset index
					} 
				}
			} 
			else 
			{														// read from recorded buffer if record = false
				record = false;
				
				for (index = 0; index < signalvectorsize; index++) 
				{
					double singlesample = 0;								// initialize sample and amplitude
					double current_amp = 0;
					
					int ireadindex = (int) read_write_index;					// values for the sample
					int ireadindexm1 = (int)(read_write_index-delta_playback);
					int ireadindex1 = (int)(read_write_index+delta_playback);
					int ireadindex2 = (int)(read_write_index+delta_playback*2);
					int ireadindex3 = (int)(read_write_index+delta_playback*3);
					double fractindex = read_write_index - ireadindex;	
					int iwreadindex = (int) window_read_index;					// values for the window
					int iwreadindexm1 = (int)(window_read_index-delta_window);
					int iwreadindex1 = (int)(window_read_index+delta_window);
					int iwreadindex2 = (int)(window_read_index+delta_window*2);
					int iwreadindex3 = (int)(window_read_index+delta_window*3);
					double wfractindex = window_read_index - iwreadindex;
					double wxm1, wx0, wx1, wx2, wx3, xm1, x0, x1, x2, x3;		// single sample/amplitude values used for interpolation

					if(iwreadindexm1 > 0){  wxm1 = window_buffer[iwreadindexm1]; } else {  wxm1 = 0; } 	// interpolate to 0 when we get to the end (or from beginning)
					wx0 = window_buffer[iwreadindex];			
					if(iwreadindex1 < window_size){  wx1 = window_buffer[iwreadindex1]; } else {  wx1 = 0; }
					if(iwreadindex2 < window_size){  wx2 = window_buffer[iwreadindex2]; } else {  wx2 = 0; wx1 = 0; }
					if(iwreadindex3 < window_size){  wx3 = window_buffer[iwreadindex3]; } else {  wx3 = 0; wx2 = 0; wx1 = 0; }
					if(ireadindexm1 > 0){ xm1 = sample_buffer[ireadindexm1]; }  else { xm1 = 0; }
					x0 = sample_buffer[ireadindex];
					if(ireadindex1 < recbufsize){ x1 = sample_buffer[ireadindex1]; } else { x1 = 0; }
					if(ireadindex2 < recbufsize){ x2 = sample_buffer[ireadindex2]; } else { x2 = 0; x2 = 0; }
					if(ireadindex3 < recbufsize){ x3 = sample_buffer[ireadindex3]; } else { x3 = 0; x2 = 0; x1 = 0; }
										
					if(interp_mode == "linear"){								// calls to interpolation methods (choose one later based on efficiency)
						current_amp = interpolate_linear(wfractindex, wx0, wx1);
						singlesample = interpolate_linear(fractindex, x0, x1);
					} 					
					else if(interp_mode == "cubic"){
						current_amp = interpolate_cubic(wfractindex, wx0, wx1, wx2, wx3);
						singlesample = interpolate_cubic(fractindex, x0, x1, x2, x3);
					}
					else if(interp_mode == "hermite"){
						current_amp = interpolate_hermite_4pt3oX(wfractindex, wx0, wx1, wx2, wx3);
						singlesample = interpolate_hermite_4pt3oX(fractindex, x0, x1, x2, x3);
					}
					else if(interp_mode == "lagrange"){
						current_amp = interpolate_lagrange_4po3oZ(wfractindex, wxm1, wx0, wx1, wx2);
						singlesample = interpolate_lagrange_4po3oZ(fractindex, xm1, x0, x1, x2);
					}
					
					out[index] = (float) (singlesample * current_amp * amplitude);			// output the stream

					read_write_index += delta_playback;										// iterate indexes
					window_read_index += delta_window;
					       										
					if ((read_write_index+delta_playback) >= recbufsize-1 || (window_read_index + delta_window) >= window_size-1)
					{
						current_amp = 0;
						window_read_index = 0;
						read_write_index = 0;
						gate = true;														// could get clicks here
					}
				}
			}
		} 
		else 
		{
			gate = true;
		//	for (index = 0; index < signalvectorsize; index++)
				//out[index] = 0;																// if gate is open then output 0s
		}
	}

	public void dspsetup(MSPSignal[] sigs_in, MSPSignal[] sigs_out) 
	{
		dsp(sigs_in, sigs_out);
	}

	public void perform(MSPSignal[] in, MSPSignal[] out) 
	{
		// if (gate == false) // dont call any performable routine unless the gate is set to false (allow iterations through signal vectors)
			p(in, out);
	}
}
