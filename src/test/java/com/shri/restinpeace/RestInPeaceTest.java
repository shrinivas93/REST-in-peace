package com.shri.restinpeace;

import com.shri.restinpeace.restclient.SampleApi;

public class RestInPeaceTest {

	public static void main(String[] args) {
		SampleApi sampleApi = RIP.getClient(SampleApi.class);
		String foo = sampleApi.foo("Shrinivas", 1993, "Shukla");
		System.out.println("Result : " + foo);
	}

}
