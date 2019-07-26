`ifndef SYNTHESIS
module DIFFAMP_SELFBIASED(
  input inn,
  input inp,
  output out );

  assign out = (inn ^ inp) ? inp : 1'b0;

endmodule
`endif
